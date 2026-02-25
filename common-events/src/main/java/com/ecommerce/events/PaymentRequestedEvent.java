package com.ecommerce.events;

public record PaymentRequestedEvent(
        String orderId,
        String userId,
        double amount
) {
}
