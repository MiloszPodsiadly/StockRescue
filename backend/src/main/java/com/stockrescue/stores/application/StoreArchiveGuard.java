package com.stockrescue.stores.application;

import java.util.UUID;

/** Domain boundary for lifecycle rules owned by workflows that reference a store. */
public interface StoreArchiveGuard {
    boolean hasOpenTransfer(UUID organizationId, UUID storeId);
}
