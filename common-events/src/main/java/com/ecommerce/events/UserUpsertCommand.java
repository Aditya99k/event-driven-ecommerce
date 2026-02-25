package com.ecommerce.events;

public record UserUpsertCommand(
        String userId,
        String name,
        String email
) {
}
