package com.ecommerce.inventory.service;

import com.ecommerce.events.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class InventorySagaHandler {

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public InventorySagaHandler(StringRedisTemplate redisTemplate, KafkaTemplate<String, Object> kafkaTemplate) {
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = TopicNames.PRODUCT_UPSERTED, groupId = "inventory-service")
    public void onProductUpserted(ProductUpsertedEvent event) {
        redisTemplate.opsForValue().set(stockKey(event.productId()), String.valueOf(event.stock()));
    }

    @KafkaListener(topics = TopicNames.ORDER_CREATED, groupId = "inventory-service")
    public void onOrderCreated(OrderCreatedEvent event) {
        for (OrderItem item : event.items()) {
            int current = currentStock(item.productId());
            if (current < item.quantity()) {
                kafkaTemplate.send(
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
        kafkaTemplate.send(TopicNames.INVENTORY_RESERVED, event.orderId(), new InventoryReservedEvent(event.orderId()));
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
}
