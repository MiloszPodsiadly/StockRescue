package com.stockrescue.inventory.application;

import java.time.Instant;
import java.util.UUID;

import com.stockrescue.inventory.domain.InventoryOperation;
import com.stockrescue.inventory.domain.StockBuckets;

public final class InventoryReadModels {
    private InventoryReadModels() { }

    public record Movement(UUID id, InventoryOperation operation, long positionVersion, StockBuckets.Delta delta,
            String reason, String evidenceReference, Instant recordedAt) { }

    public record Allocation(UUID id, UUID positionId, String bucket, long remaining, String state, long version,
            boolean ownedByCurrentActor) { }
}
