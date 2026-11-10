package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.UUID;

public record LotCorrection(UUID id,UUID lotId,UUID actorUserId,UUID actorMembershipId,long expectedVersion,
                            long resultVersion,String reason,Instant recordedAt,Kind kind,ManufacturerLot before,ManufacturerLot after) {
    public enum Kind { METADATA, SAFETY_STATUS }
}
