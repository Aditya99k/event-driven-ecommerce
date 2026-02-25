package com.ecommerce.graphql.service;

import com.ecommerce.events.*;
import com.ecommerce.graphql.dto.OrderItemInput;
import com.ecommerce.graphql.dto.PlaceOrderInput;
import com.ecommerce.graphql.dto.UpsertProductInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

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
}
