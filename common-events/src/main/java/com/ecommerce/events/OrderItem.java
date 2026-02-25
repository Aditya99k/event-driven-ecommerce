package com.ecommerce.events;

public record OrderItem(
        String productId,
        int quantity,
        double unitPrice
) {
}
