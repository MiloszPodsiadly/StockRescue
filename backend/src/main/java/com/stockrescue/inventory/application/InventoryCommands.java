package com.stockrescue.inventory.application;

import java.time.Instant;
import java.util.UUID;
import com.stockrescue.inventory.domain.StockBuckets;

public final class InventoryCommands {
    private InventoryCommands() {}
    public record PositionReference(UUID locationId,UUID productId,UUID lotId) {}
    public record ReceiveGoods(UUID requestId,PositionReference position,long quantity,String supplierKey,String documentType,
            String documentReference,String documentLineReference,Instant physicallyReceivedAt,String reason,String evidenceReference,
            Long expectedVersion) {
        public ReceiveGoods(UUID requestId,PositionReference position,long quantity,String supplierKey,String documentType,
                String documentReference,String documentLineReference,Instant physicallyReceivedAt,String reason,String evidenceReference) {
            this(requestId,position,quantity,supplierKey,documentType,documentReference,documentLineReference,physicallyReceivedAt,reason,evidenceReference,null);
        }
    }
    /** A null expectedVersion requests an atomic quantity operation against the current locked state. */
    public record StockChange(UUID requestId,UUID positionId,Long expectedVersion,long quantity,String reason,String evidenceReference) {}
    public record AllocationChange(StockChange stock,UUID allocationId) {}
    public record Result(UUID movementId,UUID positionId,long positionVersion,StockBuckets buckets,UUID allocationId,UUID receiptId) {}
}
