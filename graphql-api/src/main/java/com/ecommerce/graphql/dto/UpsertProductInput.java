package com.ecommerce.graphql.dto;

public record UpsertProductInput(
        String productId,
        String name,
        String description,
        double price,
        int stock
) {
}
