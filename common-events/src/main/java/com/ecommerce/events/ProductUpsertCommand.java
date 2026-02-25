package com.ecommerce.events;

public record ProductUpsertCommand(
        String productId,
        String name,
        String description,
        double price,
        int stock
) {
}
