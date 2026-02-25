package com.ecommerce.graphql.dto;

import java.util.List;

public record PlaceOrderInput(
        String userId,
        List<OrderItemInput> items
) {
}
