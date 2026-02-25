package com.ecommerce.events;

import java.util.List;

public record OrderCreatedEvent(
        String orderId,
        String userId,
        List<OrderItem> items,
        double totalAmount,
        OrderStatus status
) {
}
