package com.ecommerce.events;

public record PaymentCompletedEvent(
        String orderId,
        String paymentId,
        String status
) {
}
