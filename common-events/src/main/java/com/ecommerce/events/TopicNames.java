package com.ecommerce.events;

public final class TopicNames {
    public static final String PRODUCT_UPSERT_COMMAND = "catalog.product-upsert-command";
    public static final String PRODUCT_UPSERTED = "catalog.product-upserted";
    public static final String USER_UPSERT_COMMAND = "user.upsert-command";
    public static final String USER_UPSERTED = "user.upserted";
    public static final String ORDER_REQUESTED = "order.requested";
    public static final String ORDER_CREATED = "order.created";
    public static final String ORDER_STATUS_CHANGED = "order.status-changed";
    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String INVENTORY_REJECTED = "inventory.rejected";
    public static final String PAYMENT_REQUESTED = "payment.requested";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";

    private TopicNames() {
    }
}
