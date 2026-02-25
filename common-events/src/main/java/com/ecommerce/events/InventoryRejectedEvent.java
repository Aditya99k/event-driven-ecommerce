package com.ecommerce.events;

public record InventoryRejectedEvent(
        String orderId,
        String reason
) {
}
