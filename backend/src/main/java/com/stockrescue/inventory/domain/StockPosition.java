package com.stockrescue.inventory.domain;

import java.util.UUID;

public record StockPosition(UUID id, UUID organizationId, UUID storeId, UUID locationId, UUID productId, UUID lotId,
        Initialization initialization, Trust trust, StockBuckets buckets, long version) {
    public enum Initialization { NOT_INITIALIZED, INITIALIZED }
    public enum Trust { UNVERIFIED, CLEAN, FROZEN }
}
