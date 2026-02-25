package com.ecommerce.events;

import java.util.List;

public record OrderRequestedCommand(
        String orderId,
        String userId,
        List<OrderItem> items,
        double totalAmount
) {
}
