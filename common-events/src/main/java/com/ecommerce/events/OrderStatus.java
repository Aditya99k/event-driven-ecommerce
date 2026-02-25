package com.ecommerce.events;

public enum OrderStatus {
    REQUESTED,
    CREATED,
    INVENTORY_REJECTED,
    INVENTORY_RESERVED,
    PAYMENT_COMPLETED,
    PAYMENT_FAILED
}
