package com.ecommerce.order.service;

import com.ecommerce.events.*;
import com.ecommerce.order.domain.OrderEntity;
import com.ecommerce.order.domain.OrderRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderSagaHandler {
    private static final Logger log = LoggerFactory.getLogger(OrderSagaHandler.class);

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderSagaHandler(OrderRepository orderRepository, KafkaTemplate<String, Object> kafkaTemplate) {
        this.orderRepository = orderRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = TopicNames.ORDER_REQUESTED, groupId = "order-service")
    public void onOrderRequested(ConsumerRecord<String, OrderRequestedCommand> record) {
        OrderRequestedCommand command = record.value();
        logConsume(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), command);
        OrderEntity order = new OrderEntity();
        order.setId(command.orderId());
        order.setUserId(command.userId());
        order.setItems(command.items());
        order.setTotalAmount(command.totalAmount());
        order.setStatus(OrderStatus.CREATED);
        orderRepository.save(order);

        sendEvent(TopicNames.ORDER_CREATED, order.getId(), new OrderCreatedEvent(
                order.getId(),
                order.getUserId(),
                order.getItems(),
                order.getTotalAmount(),
                order.getStatus()
        ));
    }

    @KafkaListener(topics = TopicNames.INVENTORY_REJECTED, groupId = "order-service")
    public void onInventoryRejected(ConsumerRecord<String, InventoryRejectedEvent> record) {
        InventoryRejectedEvent event = record.value();
        logConsume(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.setStatus(OrderStatus.INVENTORY_REJECTED);
            order.setReason(event.reason());
            orderRepository.save(order);
            sendEvent(TopicNames.ORDER_STATUS_CHANGED, order.getId(),
                    new OrderStatusChangedEvent(order.getId(), order.getStatus(), event.reason()));
        });
    }

    @KafkaListener(topics = TopicNames.INVENTORY_RESERVED, groupId = "order-service")
    public void onInventoryReserved(ConsumerRecord<String, InventoryReservedEvent> record) {
        InventoryReservedEvent event = record.value();
        logConsume(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.setStatus(OrderStatus.INVENTORY_RESERVED);
            orderRepository.save(order);
            sendEvent(TopicNames.ORDER_STATUS_CHANGED, order.getId(),
                    new OrderStatusChangedEvent(order.getId(), order.getStatus(), null));
            sendEvent(TopicNames.PAYMENT_REQUESTED, order.getId(),
                    new PaymentRequestedEvent(order.getId(), order.getUserId(), order.getTotalAmount()));
        });
    }

    @KafkaListener(topics = TopicNames.PAYMENT_COMPLETED, groupId = "order-service")
    public void onPaymentCompleted(ConsumerRecord<String, PaymentCompletedEvent> record) {
        PaymentCompletedEvent event = record.value();
        logConsume(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.setStatus(OrderStatus.PAYMENT_COMPLETED);
            orderRepository.save(order);
            sendEvent(TopicNames.ORDER_STATUS_CHANGED, order.getId(),
                    new OrderStatusChangedEvent(order.getId(), order.getStatus(), event.status()));
        });
    }

    @KafkaListener(topics = TopicNames.PAYMENT_FAILED, groupId = "order-service")
    public void onPaymentFailed(ConsumerRecord<String, PaymentFailedEvent> record) {
        PaymentFailedEvent event = record.value();
        logConsume(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.setStatus(OrderStatus.PAYMENT_FAILED);
            order.setReason(event.reason());
            orderRepository.save(order);
            sendEvent(TopicNames.ORDER_STATUS_CHANGED, order.getId(),
                    new OrderStatusChangedEvent(order.getId(), order.getStatus(), event.reason()));
        });
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
