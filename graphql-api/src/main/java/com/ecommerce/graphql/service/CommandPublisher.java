package com.ecommerce.graphql.service;

import com.ecommerce.events.*;
import com.ecommerce.graphql.dto.PlaceOrderInput;
import com.ecommerce.graphql.dto.UpsertProductInput;
import com.ecommerce.graphql.dto.UpsertUserInput;
import com.ecommerce.graphql.idempotency.IdempotencyRecord;
import com.ecommerce.graphql.idempotency.IdempotencyRecordRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class CommandPublisher {
    private static final Logger log = LoggerFactory.getLogger(CommandPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public CommandPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                            IdempotencyRecordRepository idempotencyRecordRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }

    public String placeOrder(PlaceOrderInput input, String idempotencyKey) {
        List<OrderItem> items = input.items().stream()
                .map(item -> new OrderItem(item.productId(), item.quantity(), item.unitPrice()))
                .toList();
        double total = items.stream().mapToDouble(i -> i.unitPrice() * i.quantity()).sum();

        String requestFingerprint = hash("userId=" + input.userId() + "|items=" + items + "|total=" + total);
        return executeIdempotent("placeOrder", idempotencyKey, requestFingerprint, () -> UUID.randomUUID().toString(), orderId -> {
            OrderRequestedCommand command = new OrderRequestedCommand(orderId, input.userId(), items, total);
            sendEvent(TopicNames.ORDER_REQUESTED, orderId, command);
        });
    }

    public String upsertProduct(UpsertProductInput input, String idempotencyKey) {
        String requestFingerprint = hash(
                "productId=" + input.productId() + "|name=" + input.name() + "|description=" + input.description()
                        + "|price=" + input.price() + "|stock=" + input.stock()
        );

        return executeIdempotent("upsertProduct", idempotencyKey, requestFingerprint,
                () -> input.productId() == null || input.productId().isBlank() ? UUID.randomUUID().toString() : input.productId(),
                productId -> {
                    ProductUpsertCommand command = new ProductUpsertCommand(
                            productId,
                            input.name(),
                            input.description(),
                            input.price(),
                            input.stock()
                    );
                    sendEvent(TopicNames.PRODUCT_UPSERT_COMMAND, productId, command);
                });
    }

    public String upsertUser(UpsertUserInput input, String idempotencyKey) {
        String requestFingerprint = hash(
                "userId=" + input.userId() + "|name=" + input.name() + "|email=" + input.email()
        );

        return executeIdempotent("upsertUser", idempotencyKey, requestFingerprint,
                () -> input.userId() == null || input.userId().isBlank() ? UUID.randomUUID().toString() : input.userId(),
                userId -> {
                    UserUpsertCommand command = new UserUpsertCommand(
                            userId,
                            input.name(),
                            input.email()
                    );
                    sendEvent(TopicNames.USER_UPSERT_COMMAND, userId, command);
                });
    }

    private String executeIdempotent(String operation,
                                     String idempotencyKey,
                                     String requestHash,
                                     Supplier<String> responseIdSupplier,
                                     java.util.function.Consumer<String> publisher) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }

        String storageId = operation + ":" + idempotencyKey;
        IdempotencyRecord existing = idempotencyRecordRepository.findById(storageId).orElse(null);
        if (existing != null) {
            assertSamePayload(existing, requestHash, operation, idempotencyKey);
            if (existing.isPublished()) {
                log.info("Idempotent replay served from store: operation={} idempotencyKey={} responseId={}",
                        operation, idempotencyKey, existing.getResponseId());
                return existing.getResponseId();
            }
            // Previous publish failed; retry publishing with same generated response id.
            publisher.accept(existing.getResponseId());
            existing.setPublished(true);
            existing.setLastError(null);
            existing.setUpdatedAt(Instant.now());
            idempotencyRecordRepository.save(existing);
            return existing.getResponseId();
        }

        String responseId = responseIdSupplier.get();
        IdempotencyRecord record = new IdempotencyRecord();
        record.setId(storageId);
        record.setOperation(operation);
        record.setKey(idempotencyKey);
        record.setRequestHash(requestHash);
        record.setResponseId(responseId);
        record.setPublished(false);
        record.setCreatedAt(Instant.now());
        record.setUpdatedAt(Instant.now());

        try {
            idempotencyRecordRepository.insert(record);
        } catch (DuplicateKeyException duplicateKeyException) {
            IdempotencyRecord concurrent = idempotencyRecordRepository.findById(storageId)
                    .orElseThrow(() -> duplicateKeyException);
            assertSamePayload(concurrent, requestHash, operation, idempotencyKey);
            return concurrent.getResponseId();
        }

        try {
            publisher.accept(responseId);
            record.setPublished(true);
            record.setUpdatedAt(Instant.now());
            idempotencyRecordRepository.save(record);
            return responseId;
        } catch (RuntimeException ex) {
            record.setLastError(ex.getMessage());
            record.setUpdatedAt(Instant.now());
            idempotencyRecordRepository.save(record);
            throw ex;
        }
    }

    private void assertSamePayload(IdempotencyRecord existing,
                                   String requestHash,
                                   String operation,
                                   String idempotencyKey) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IllegalArgumentException(
                    "Idempotency key reuse with different payload is not allowed. operation="
                            + operation + " key=" + idempotencyKey
            );
        }
    }

    private void sendEvent(String topic, String key, Object payload) {
        String correlationId = currentCorrelationId();
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, payload);
        record.headers().add(TraceHeaders.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));

        try {
            var result = kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
            var metadata = result.getRecordMetadata();
            log.info("Kafka produced: correlationId={} topic={} partition={} offset={} timestamp={} key={} payload={}",
                    correlationId, metadata.topic(), metadata.partition(), metadata.offset(), metadata.timestamp(), key, payload);
        } catch (Exception ex) {
            log.error("Kafka produce failed: correlationId={} topic={} key={} payload={} error={}",
                    correlationId, topic, key, payload, ex.getMessage(), ex);
            throw new RuntimeException("Kafka publish failed for topic " + topic, ex);
        }
    }

    private String currentCorrelationId() {
        String correlationId = MDC.get(TraceHeaders.CORRELATION_ID);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
            MDC.put(TraceHeaders.CORRELATION_ID, correlationId);
        }
        return correlationId;
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to hash idempotency payload", ex);
        }
    }
}
