package com.ecommerce.inventory.service;

import com.ecommerce.events.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class InventorySagaHandler {
    private static final Logger log = LoggerFactory.getLogger(InventorySagaHandler.class);

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public InventorySagaHandler(StringRedisTemplate redisTemplate, KafkaTemplate<String, Object> kafkaTemplate) {
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = TopicNames.PRODUCT_UPSERTED, groupId = "inventory-service")
    public void onProductUpserted(ConsumerRecord<String, ProductUpsertedEvent> record) {
        ProductUpsertedEvent event = record.value();
        logConsume(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
        redisTemplate.opsForValue().set(stockKey(event.productId()), String.valueOf(event.stock()));
    }

    @KafkaListener(topics = TopicNames.ORDER_CREATED, groupId = "inventory-service")
    public void onOrderCreated(ConsumerRecord<String, OrderCreatedEvent> record) {
        OrderCreatedEvent event = record.value();
        logConsume(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
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
        }
        sendEvent(TopicNames.INVENTORY_RESERVED, event.orderId(), new InventoryReservedEvent(event.orderId()));
    }

    private int currentStock(String productId) {
        String value = redisTemplate.opsForValue().get(stockKey(productId));
        if (value == null) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    private String stockKey(String productId) {
        return "inventory:stock:" + productId;
    }

    private void sendEvent(String topic, String key, Object payload) {
        kafkaTemplate.send(topic, key, payload).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Kafka produce failed: topic={} key={} payload={} error={}",
                        topic, key, payload, ex.getMessage(), ex);
                return;
            }
            var metadata = result.getRecordMetadata();
            log.info("Kafka produced: topic={} partition={} offset={} timestamp={} key={} payload={}",
                    metadata.topic(), metadata.partition(), metadata.offset(), metadata.timestamp(), key, payload);
        });
    }

    private void logConsume(String topic, int partition, long offset, long timestamp, String key, Object payload) {
        log.info("Kafka consumed: topic={} partition={} offset={} timestamp={} key={} payload={}",
                topic, partition, offset, timestamp, key, payload);
    }
}
