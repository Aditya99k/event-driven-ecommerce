package com.ecommerce.events;

public record UserUpsertedEvent(
        String userId,
        String name,
        String email
) {
}
