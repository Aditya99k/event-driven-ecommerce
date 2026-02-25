package com.ecommerce.events;

public record ProductUpsertedEvent(
        String productId,
        String name,
        String description,
        double price,
        int stock
) {
}
