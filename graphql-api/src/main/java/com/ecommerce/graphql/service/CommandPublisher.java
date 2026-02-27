package com.ecommerce.graphql.service;

import com.ecommerce.events.*;
import com.ecommerce.graphql.dto.PlaceOrderInput;
import com.ecommerce.graphql.dto.UpsertProductInput;
import com.ecommerce.graphql.dto.UpsertUserInput;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
public class CommandPublisher {
    private static final Logger log = LoggerFactory.getLogger(CommandPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public CommandPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public String placeOrder(PlaceOrderInput input) {
        String orderId = UUID.randomUUID().toString();
        List<OrderItem> items = input.items().stream()
                .map(item -> new OrderItem(item.productId(), item.quantity(), item.unitPrice()))
                .toList();
        double total = items.stream().mapToDouble(i -> i.unitPrice() * i.quantity()).sum();

        OrderRequestedCommand command = new OrderRequestedCommand(orderId, input.userId(), items, total);
        sendEvent(TopicNames.ORDER_REQUESTED, orderId, command);
        return orderId;
    }

    public String upsertProduct(UpsertProductInput input) {
        String productId = input.productId() == null || input.productId().isBlank()
                ? UUID.randomUUID().toString()
                : input.productId();

        ProductUpsertCommand command = new ProductUpsertCommand(
                productId,
                input.name(),
                input.description(),
                input.price(),
                input.stock()
        );
        sendEvent(TopicNames.PRODUCT_UPSERT_COMMAND, productId, command);
        return productId;
    }

    public String upsertUser(UpsertUserInput input) {
        String userId = input.userId() == null || input.userId().isBlank()
                ? UUID.randomUUID().toString()
                : input.userId();

        UserUpsertCommand command = new UserUpsertCommand(
                userId,
                input.name(),
                input.email()
        );
        sendEvent(TopicNames.USER_UPSERT_COMMAND, userId, command);
        return userId;
    }

    private void sendEvent(String topic, String key, Object payload) {
        String correlationId = currentCorrelationId();
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, payload);
        record.headers().add(TraceHeaders.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));

        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Kafka produce failed: correlationId={} topic={} key={} payload={} error={}",
                        correlationId, topic, key, payload, ex.getMessage(), ex);
                return;
            }
            var metadata = result.getRecordMetadata();
            log.info("Kafka produced: correlationId={} topic={} partition={} offset={} timestamp={} key={} payload={}",
                    correlationId, metadata.topic(), metadata.partition(), metadata.offset(), metadata.timestamp(), key, payload);
        });
    }

    private String currentCorrelationId() {
        String correlationId = MDC.get(TraceHeaders.CORRELATION_ID);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
            MDC.put(TraceHeaders.CORRELATION_ID, correlationId);
        }
        return correlationId;
    }
}
