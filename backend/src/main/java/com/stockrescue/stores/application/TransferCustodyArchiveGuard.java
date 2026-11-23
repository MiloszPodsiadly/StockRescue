package com.stockrescue.stores.application;

import java.util.UUID;

/** Prevents archiving physical contexts while a transfer can still settle against them. */
public interface TransferCustodyArchiveGuard {
    boolean hasOpenTransferForProduct(UUID organizationId, UUID productId);
    boolean hasOpenTransferForLocation(UUID organizationId, UUID storeId, UUID locationId);
}
