package com.ecommerce.graphql.service;

import com.ecommerce.events.*;
import com.ecommerce.graphql.projection.OrderView;
import com.ecommerce.graphql.projection.OrderViewRepository;
import com.ecommerce.graphql.projection.ProductView;
import com.ecommerce.graphql.projection.ProductViewRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class ProjectionUpdater {

    private final ProductViewRepository productViewRepository;
    private final OrderViewRepository orderViewRepository;

    public ProjectionUpdater(ProductViewRepository productViewRepository, OrderViewRepository orderViewRepository) {
        this.productViewRepository = productViewRepository;
        this.orderViewRepository = orderViewRepository;
    }

    @KafkaListener(topics = TopicNames.PRODUCT_UPSERTED, groupId = "graphql-api")
    public void onProductUpserted(ProductUpsertedEvent event) {
        ProductView view = new ProductView();
        view.setId(event.productId());
        view.setName(event.name());
        view.setDescription(event.description());
        view.setPrice(event.price());
        view.setStock(event.stock());
        productViewRepository.save(view);
    }

    @KafkaListener(topics = TopicNames.ORDER_CREATED, groupId = "graphql-api")
    public void onOrderCreated(OrderCreatedEvent event) {
        OrderView view = new OrderView();
        view.setId(event.orderId());
        view.setUserId(event.userId());
        view.setItems(event.items());
        view.setTotalAmount(event.totalAmount());
        view.setStatus(event.status());
        orderViewRepository.save(view);
    }

    @KafkaListener(topics = TopicNames.ORDER_STATUS_CHANGED, groupId = "graphql-api")
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        orderViewRepository.findById(event.orderId()).ifPresent(view -> {
            view.setStatus(event.status());
            view.setReason(event.reason());
            orderViewRepository.save(view);
        });
    }

    @KafkaListener(topics = TopicNames.INVENTORY_REJECTED, groupId = "graphql-api")
    public void onInventoryRejected(InventoryRejectedEvent event) {
        orderViewRepository.findById(event.orderId()).ifPresent(view -> {
            view.setStatus(OrderStatus.INVENTORY_REJECTED);
            view.setReason(event.reason());
            orderViewRepository.save(view);
        });
    }

    @KafkaListener(topics = TopicNames.PAYMENT_COMPLETED, groupId = "graphql-api")
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        orderViewRepository.findById(event.orderId()).ifPresent(view -> {
            view.setStatus(OrderStatus.PAYMENT_COMPLETED);
            view.setReason(event.status());
            orderViewRepository.save(view);
        });
    }
}
