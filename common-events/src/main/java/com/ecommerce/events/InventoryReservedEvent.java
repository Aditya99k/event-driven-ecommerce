package com.ecommerce.events;

public record InventoryReservedEvent(
        String orderId
) {
}
