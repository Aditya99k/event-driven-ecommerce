package com.ecommerce.graphql.dto;

public record OrderItemInput(
        String productId,
        int quantity,
        double unitPrice
) {
}
