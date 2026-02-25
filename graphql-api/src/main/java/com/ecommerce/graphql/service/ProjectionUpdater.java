package com.ecommerce.graphql.service;

import com.ecommerce.events.*;
import com.ecommerce.graphql.projection.OrderView;
import com.ecommerce.graphql.projection.OrderViewRepository;
import com.ecommerce.graphql.projection.ProductView;
import com.ecommerce.graphql.projection.ProductViewRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class ProjectionUpdater {
    private static final Logger log = LoggerFactory.getLogger(ProjectionUpdater.class);

    private final ProductViewRepository productViewRepository;
    private final OrderViewRepository orderViewRepository;

    public ProjectionUpdater(ProductViewRepository productViewRepository, OrderViewRepository orderViewRepository) {
        this.productViewRepository = productViewRepository;
        this.orderViewRepository = orderViewRepository;
    }

    @KafkaListener(topics = TopicNames.PRODUCT_UPSERTED, groupId = "graphql-api")
    public void onProductUpserted(ConsumerRecord<String, ProductUpsertedEvent> record) {
        ProductUpsertedEvent event = record.value();
        logConsume(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
        ProductView view = new ProductView();
        view.setId(event.productId());
        view.setName(event.name());
        view.setDescription(event.description());
        view.setPrice(event.price());
        view.setStock(event.stock());
        productViewRepository.save(view);
    }

    @KafkaListener(topics = TopicNames.ORDER_CREATED, groupId = "graphql-api")
    public void onOrderCreated(ConsumerRecord<String, OrderCreatedEvent> record) {
        OrderCreatedEvent event = record.value();
        logConsume(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
        OrderView view = new OrderView();
        view.setId(event.orderId());
        view.setUserId(event.userId());
        view.setItems(event.items());
        view.setTotalAmount(event.totalAmount());
        view.setStatus(event.status());
        orderViewRepository.save(view);
    }

    @KafkaListener(topics = TopicNames.ORDER_STATUS_CHANGED, groupId = "graphql-api")
    public void onOrderStatusChanged(ConsumerRecord<String, OrderStatusChangedEvent> record) {
        OrderStatusChangedEvent event = record.value();
        logConsume(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
        orderViewRepository.findById(event.orderId()).ifPresent(view -> {
            view.setStatus(event.status());
            view.setReason(event.reason());
            orderViewRepository.save(view);
        });
    }

    @KafkaListener(topics = TopicNames.INVENTORY_REJECTED, groupId = "graphql-api")
    public void onInventoryRejected(ConsumerRecord<String, InventoryRejectedEvent> record) {
        InventoryRejectedEvent event = record.value();
        logConsume(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
        orderViewRepository.findById(event.orderId()).ifPresent(view -> {
            view.setStatus(OrderStatus.INVENTORY_REJECTED);
            view.setReason(event.reason());
            orderViewRepository.save(view);
        });
    }

    @KafkaListener(topics = TopicNames.PAYMENT_COMPLETED, groupId = "graphql-api")
    public void onPaymentCompleted(ConsumerRecord<String, PaymentCompletedEvent> record) {
        PaymentCompletedEvent event = record.value();
        logConsume(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), event);
        orderViewRepository.findById(event.orderId()).ifPresent(view -> {
            view.setStatus(OrderStatus.PAYMENT_COMPLETED);
            view.setReason(event.status());
            orderViewRepository.save(view);
        });
    }

    private void logConsume(String topic, int partition, long offset, long timestamp, String key, Object payload) {
        log.info("Kafka consumed: topic={} partition={} offset={} timestamp={} key={} payload={}",
                topic, partition, offset, timestamp, key, payload);
    }
}
