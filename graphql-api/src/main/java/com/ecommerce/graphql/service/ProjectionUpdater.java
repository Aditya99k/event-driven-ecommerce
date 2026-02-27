package com.ecommerce.graphql.service;

import com.ecommerce.events.*;
import com.ecommerce.graphql.projection.OrderView;
import com.ecommerce.graphql.projection.OrderViewRepository;
import com.ecommerce.graphql.projection.ProductView;
import com.ecommerce.graphql.projection.ProductViewRepository;
import com.ecommerce.graphql.projection.UserView;
import com.ecommerce.graphql.projection.UserViewRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class ProjectionUpdater {
    private static final Logger log = LoggerFactory.getLogger(ProjectionUpdater.class);

    private final ProductViewRepository productViewRepository;
    private final OrderViewRepository orderViewRepository;
    private final UserViewRepository userViewRepository;

    public ProjectionUpdater(ProductViewRepository productViewRepository,
                             OrderViewRepository orderViewRepository,
                             UserViewRepository userViewRepository) {
        this.productViewRepository = productViewRepository;
        this.orderViewRepository = orderViewRepository;
        this.userViewRepository = userViewRepository;
    }

    @KafkaListener(topics = TopicNames.PRODUCT_UPSERTED, groupId = "graphql-api")
    public void onProductUpserted(ConsumerRecord<String, ProductUpsertedEvent> record) {
        withCorrelation(record, () -> {
            ProductUpsertedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            ProductView view = new ProductView();
            view.setId(event.productId());
            view.setName(event.name());
            view.setDescription(event.description());
            view.setPrice(event.price());
            view.setStock(event.stock());
            productViewRepository.save(view);
        });
    }

    @KafkaListener(topics = TopicNames.ORDER_CREATED, groupId = "graphql-api")
    public void onOrderCreated(ConsumerRecord<String, OrderCreatedEvent> record) {
        withCorrelation(record, () -> {
            OrderCreatedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            OrderView view = new OrderView();
            view.setId(event.orderId());
            view.setUserId(event.userId());
            view.setItems(event.items());
            view.setTotalAmount(event.totalAmount());
            view.setStatus(event.status());
            orderViewRepository.save(view);
        });
    }

    @KafkaListener(topics = TopicNames.ORDER_STATUS_CHANGED, groupId = "graphql-api")
    public void onOrderStatusChanged(ConsumerRecord<String, OrderStatusChangedEvent> record) {
        withCorrelation(record, () -> {
            OrderStatusChangedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            orderViewRepository.findById(event.orderId()).ifPresent(view -> {
                view.setStatus(event.status());
                view.setReason(event.reason());
                orderViewRepository.save(view);
            });
        });
    }

    @KafkaListener(topics = TopicNames.INVENTORY_REJECTED, groupId = "graphql-api")
    public void onInventoryRejected(ConsumerRecord<String, InventoryRejectedEvent> record) {
        withCorrelation(record, () -> {
            InventoryRejectedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            orderViewRepository.findById(event.orderId()).ifPresent(view -> {
                view.setStatus(OrderStatus.INVENTORY_REJECTED);
                view.setReason(event.reason());
                orderViewRepository.save(view);
            });
        });
    }

    @KafkaListener(topics = TopicNames.PAYMENT_COMPLETED, groupId = "graphql-api")
    public void onPaymentCompleted(ConsumerRecord<String, PaymentCompletedEvent> record) {
        withCorrelation(record, () -> {
            PaymentCompletedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            orderViewRepository.findById(event.orderId()).ifPresent(view -> {
                view.setStatus(OrderStatus.PAYMENT_COMPLETED);
                view.setReason(event.status());
                orderViewRepository.save(view);
            });
        });
    }

    @KafkaListener(topics = TopicNames.USER_UPSERTED, groupId = "graphql-api")
    public void onUserUpserted(ConsumerRecord<String, UserUpsertedEvent> record) {
        withCorrelation(record, () -> {
            UserUpsertedEvent event = record.value();
            logConsume(currentCorrelationId(), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
            UserView view = new UserView();
            view.setId(event.userId());
            view.setName(event.name());
            view.setEmail(event.email());
            userViewRepository.save(view);
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
