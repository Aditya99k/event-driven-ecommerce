package com.ecommerce.events;

public record OrderStatusChangedEvent(
        String orderId,
        OrderStatus status,
        String reason
) {
}
