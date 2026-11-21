package com.stockrescue.transfers.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferAggregateEvent(UUID eventId,String eventType,int schemaVersion,Instant occurredAt,UUID orgId,
        UUID transferId,long aggregateVersion,String correlationId,UUID causationId,UUID commandId,
        String dataClassification,Data data) {
    public record Data(String operation,String statusBefore,String statusAfter,UUID sourceStoreId,UUID destinationStoreId,
            List<StockMovementRef> stockMovements,List<CustodyMovementRef> custodyMovements) {
        public Data { stockMovements=List.copyOf(stockMovements);custodyMovements=List.copyOf(custodyMovements); }
    }
    public record StockMovementRef(UUID storeId,UUID movementId) { }
    public record CustodyMovementRef(UUID id,UUID lineId,String movementType,long quantity,UUID stockStoreId,
            UUID stockMovementId,UUID stockPositionId) { }
}
