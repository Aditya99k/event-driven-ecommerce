package com.ecommerce.graphql.service;

import com.ecommerce.events.*;
import com.ecommerce.graphql.dto.OrderItemInput;
import com.ecommerce.graphql.dto.PlaceOrderInput;
import com.ecommerce.graphql.dto.UpsertProductInput;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class CommandPublisher {

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
        kafkaTemplate.send(TopicNames.ORDER_REQUESTED, orderId, command);
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
        kafkaTemplate.send(TopicNames.PRODUCT_UPSERT_COMMAND, productId, command);
        return productId;
    }
}
