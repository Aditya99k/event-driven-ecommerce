package com.ecommerce.graphql.dto;

public record UpsertUserInput(
        String userId,
        String name,
        String email
) {
}
