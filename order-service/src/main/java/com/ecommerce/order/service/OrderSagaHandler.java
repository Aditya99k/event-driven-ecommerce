package com.ecommerce.order.service;

import com.ecommerce.events.*;
import com.ecommerce.order.domain.OrderEntity;
import com.ecommerce.order.domain.OrderRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
        withCorrelation(record, () -> {
            OrderRequestedCommand command = record.value();
            String correlationId = currentCorrelationId();
            logConsume(correlationId, record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), command);

            // Idempotency: ignore duplicate order request for an existing order ID.
            if (orderRepository.existsById(command.orderId())) {
                log.info("Duplicate order request ignored: correlationId={} orderId={}", correlationId, command.orderId());
                return;
            }

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
        });
    }

    @KafkaListener(topics = TopicNames.INVENTORY_REJECTED, groupId = "order-service")
    public void onInventoryRejected(ConsumerRecord<String, InventoryRejectedEvent> record) {
        withCorrelation(record, () -> {
            InventoryRejectedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            orderRepository.findById(event.orderId()).ifPresent(order -> {
                order.setStatus(OrderStatus.INVENTORY_REJECTED);
                order.setReason(event.reason());
                orderRepository.save(order);
                sendEvent(TopicNames.ORDER_STATUS_CHANGED, order.getId(),
                        new OrderStatusChangedEvent(order.getId(), order.getStatus(), event.reason()));
            });
        });
    }

    @KafkaListener(topics = TopicNames.INVENTORY_RESERVED, groupId = "order-service")
    public void onInventoryReserved(ConsumerRecord<String, InventoryReservedEvent> record) {
        withCorrelation(record, () -> {
            InventoryReservedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            orderRepository.findById(event.orderId()).ifPresent(order -> {
                order.setStatus(OrderStatus.INVENTORY_RESERVED);
                orderRepository.save(order);
                sendEvent(TopicNames.ORDER_STATUS_CHANGED, order.getId(),
                        new OrderStatusChangedEvent(order.getId(), order.getStatus(), null));
                sendEvent(TopicNames.PAYMENT_REQUESTED, order.getId(),
                        new PaymentRequestedEvent(order.getId(), order.getUserId(), order.getTotalAmount()));
            });
        });
    }

    @KafkaListener(topics = TopicNames.PAYMENT_COMPLETED, groupId = "order-service")
    public void onPaymentCompleted(ConsumerRecord<String, PaymentCompletedEvent> record) {
        withCorrelation(record, () -> {
            PaymentCompletedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            orderRepository.findById(event.orderId()).ifPresent(order -> {
                order.setStatus(OrderStatus.PAYMENT_COMPLETED);
                orderRepository.save(order);
                sendEvent(TopicNames.ORDER_STATUS_CHANGED, order.getId(),
                        new OrderStatusChangedEvent(order.getId(), order.getStatus(), event.status()));
            });
        });
    }

    @KafkaListener(topics = TopicNames.PAYMENT_FAILED, groupId = "order-service")
    public void onPaymentFailed(ConsumerRecord<String, PaymentFailedEvent> record) {
        withCorrelation(record, () -> {
            PaymentFailedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            orderRepository.findById(event.orderId()).ifPresent(order -> {
                order.setStatus(OrderStatus.PAYMENT_FAILED);
                order.setReason(event.reason());
                orderRepository.save(order);
                sendEvent(TopicNames.ORDER_STATUS_CHANGED, order.getId(),
                        new OrderStatusChangedEvent(order.getId(), order.getStatus(), event.reason()));
            });
        });
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
