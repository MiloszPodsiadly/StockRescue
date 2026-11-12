package com.stockrescue.inventory.messaging;

import java.time.Instant;
import java.util.UUID;

public record InventoryStockChangedEvent(UUID eventId,String eventType,int schemaVersion,Instant occurredAt,UUID tenantId,
        String aggregateType,UUID aggregateId,long aggregateVersion,UUID correlationId,UUID causationId,String dataClassification,
        Data data) {
    public record Data(UUID storeId,UUID movementId,String unit,Delta delta,Position position) {}
    public record Delta(long onHand,long reserved,long quarantined,long otherBlocked) {}
    public record Position(long onHand,long reserved,long quarantined,long otherBlocked,long available,String trust) {}
}
