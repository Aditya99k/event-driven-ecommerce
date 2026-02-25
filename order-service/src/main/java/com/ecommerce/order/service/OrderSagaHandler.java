package com.ecommerce.order.service;

import com.ecommerce.events.*;
import com.ecommerce.order.domain.OrderEntity;
import com.ecommerce.order.domain.OrderRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderSagaHandler {

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderSagaHandler(OrderRepository orderRepository, KafkaTemplate<String, Object> kafkaTemplate) {
        this.orderRepository = orderRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = TopicNames.ORDER_REQUESTED, groupId = "order-service")
    public void onOrderRequested(OrderRequestedCommand command) {
        OrderEntity order = new OrderEntity();
        order.setId(command.orderId());
        order.setUserId(command.userId());
        order.setItems(command.items());
        order.setTotalAmount(command.totalAmount());
        order.setStatus(OrderStatus.CREATED);
        orderRepository.save(order);

        kafkaTemplate.send(TopicNames.ORDER_CREATED, order.getId(), new OrderCreatedEvent(
                order.getId(),
                order.getUserId(),
                order.getItems(),
                order.getTotalAmount(),
                order.getStatus()
        ));
    }

    @KafkaListener(topics = TopicNames.INVENTORY_REJECTED, groupId = "order-service")
    public void onInventoryRejected(InventoryRejectedEvent event) {
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.setStatus(OrderStatus.INVENTORY_REJECTED);
            order.setReason(event.reason());
            orderRepository.save(order);
            kafkaTemplate.send(TopicNames.ORDER_STATUS_CHANGED, order.getId(),
                    new OrderStatusChangedEvent(order.getId(), order.getStatus(), event.reason()));
        });
    }

    @KafkaListener(topics = TopicNames.INVENTORY_RESERVED, groupId = "order-service")
    public void onInventoryReserved(InventoryReservedEvent event) {
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.setStatus(OrderStatus.INVENTORY_RESERVED);
            orderRepository.save(order);
            kafkaTemplate.send(TopicNames.ORDER_STATUS_CHANGED, order.getId(),
                    new OrderStatusChangedEvent(order.getId(), order.getStatus(), null));
            kafkaTemplate.send(TopicNames.PAYMENT_REQUESTED, order.getId(),
                    new PaymentRequestedEvent(order.getId(), order.getUserId(), order.getTotalAmount()));
        });
    }

    @KafkaListener(topics = TopicNames.PAYMENT_COMPLETED, groupId = "order-service")
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.setStatus(OrderStatus.PAYMENT_COMPLETED);
            orderRepository.save(order);
            kafkaTemplate.send(TopicNames.ORDER_STATUS_CHANGED, order.getId(),
                    new OrderStatusChangedEvent(order.getId(), order.getStatus(), event.status()));
        });
    }

    @KafkaListener(topics = TopicNames.PAYMENT_FAILED, groupId = "order-service")
    public void onPaymentFailed(PaymentFailedEvent event) {
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.setStatus(OrderStatus.PAYMENT_FAILED);
            order.setReason(event.reason());
            orderRepository.save(order);
            kafkaTemplate.send(TopicNames.ORDER_STATUS_CHANGED, order.getId(),
                    new OrderStatusChangedEvent(order.getId(), order.getStatus(), event.reason()));
        });
    }
}
