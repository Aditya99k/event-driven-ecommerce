package com.ecommerce.inventory.service;

import com.ecommerce.events.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class InventorySagaHandler {
    private static final Logger log = LoggerFactory.getLogger(InventorySagaHandler.class);
    private static final String STOCK_KEY_PREFIX = "inventory:stock:";
    private static final String RESERVATION_KEY_PREFIX = "inventory:reservation:";

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public InventorySagaHandler(StringRedisTemplate redisTemplate, KafkaTemplate<String, Object> kafkaTemplate) {
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = TopicNames.PRODUCT_UPSERTED, groupId = "inventory-service")
    public void onProductUpserted(ConsumerRecord<String, ProductUpsertedEvent> record) {
        withCorrelation(record, () -> {
            ProductUpsertedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            redisTemplate.opsForValue().set(stockKey(event.productId()), String.valueOf(event.stock()));
        });
    }

    @KafkaListener(topics = TopicNames.ORDER_CREATED, groupId = "inventory-service")
    public void onOrderCreated(ConsumerRecord<String, OrderCreatedEvent> record) {
        withCorrelation(record, () -> {
            OrderCreatedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            for (OrderItem item : event.items()) {
                int current = currentStock(item.productId());
                if (current < item.quantity()) {
                    sendEvent(
                            TopicNames.INVENTORY_REJECTED,
                            event.orderId(),
                            new InventoryRejectedEvent(event.orderId(), "Insufficient stock for product " + item.productId())
                    );
                    return;
                }
            }

            for (OrderItem item : event.items()) {
                redisTemplate.opsForValue().decrement(stockKey(item.productId()), item.quantity());
                redisTemplate.opsForHash().put(reservationKey(event.orderId()), item.productId(), String.valueOf(item.quantity()));
            }
            redisTemplate.expire(reservationKey(event.orderId()), Duration.ofHours(24));
            sendEvent(TopicNames.INVENTORY_RESERVED, event.orderId(), new InventoryReservedEvent(event.orderId()));
        });
    }

    @KafkaListener(topics = TopicNames.PAYMENT_FAILED, groupId = "inventory-service")
    public void onPaymentFailed(ConsumerRecord<String, PaymentFailedEvent> record) {
        withCorrelation(record, () -> {
            PaymentFailedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            releaseReservation(event.orderId(), true);
        });
    }

    @KafkaListener(topics = TopicNames.PAYMENT_COMPLETED, groupId = "inventory-service")
    public void onPaymentCompleted(ConsumerRecord<String, PaymentCompletedEvent> record) {
        withCorrelation(record, () -> {
            PaymentCompletedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            releaseReservation(event.orderId(), false);
        });
    }

    private void releaseReservation(String orderId, boolean restoreStock) {
        String key = reservationKey(orderId);
        Map<Object, Object> reservedItems = redisTemplate.opsForHash().entries(key);
        if (reservedItems.isEmpty()) {
            return;
        }

        if (restoreStock) {
            reservedItems.forEach((productId, qty) -> redisTemplate.opsForValue()
                    .increment(stockKey(String.valueOf(productId)), Long.parseLong(String.valueOf(qty))));
            log.info("Inventory compensation completed: correlationId={} orderId={} restoredItems={}",
                    currentCorrelationId(), orderId, reservedItems);
        }
        redisTemplate.delete(key);
    }

    private int currentStock(String productId) {
        String value = redisTemplate.opsForValue().get(stockKey(productId));
        if (value == null) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    private String stockKey(String productId) {
        return STOCK_KEY_PREFIX + productId;
    }

    private String reservationKey(String orderId) {
        return RESERVATION_KEY_PREFIX + orderId;
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

    private void logConsume(String correlationId,
                            String topic,
                            int partition,
                            long offset,
                            long timestamp,
                            String key,
                            Object payload) {
        log.info("Kafka consumed: correlationId={} topic={} partition={} offset={} timestamp={} key={} payload={}",
                correlationId, topic, partition, offset, timestamp, key, payload);
    }

    private void withCorrelation(ConsumerRecord<?, ?> record, Runnable runnable) {
        String correlationId = extractCorrelationId(record.headers());
        MDC.put(TraceHeaders.CORRELATION_ID, correlationId);
        try {
            runnable.run();
        } finally {
            MDC.remove(TraceHeaders.CORRELATION_ID);
        }
    }

    private String extractCorrelationId(Headers headers) {
        Header header = headers.lastHeader(TraceHeaders.CORRELATION_ID);
        if (header == null || header.value() == null || header.value().length == 0) {
            return UUID.randomUUID().toString();
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private String currentCorrelationId() {
        String correlationId = MDC.get(TraceHeaders.CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
            MDC.put(TraceHeaders.CORRELATION_ID, correlationId);
        }
        return correlationId;
    }
}
