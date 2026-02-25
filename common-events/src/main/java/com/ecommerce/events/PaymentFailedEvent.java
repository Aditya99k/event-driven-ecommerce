package com.ecommerce.events;

public record PaymentFailedEvent(
        String orderId,
        String reason
) {
}
