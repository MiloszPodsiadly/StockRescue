package com.stockrescue.stores.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OperationalConfiguration {
    private OperationalConfiguration() { }
    public enum TemperatureClass { AMBIENT, CHILLED, FROZEN }
    public enum Status { CONFIGURED, UNCONFIGURED, INACTIVE }
    public enum Missing { NO_LANE, INACTIVE_LANE, INACTIVE_STORE, MISSING_ZONES, INACTIVE_ZONE, INCOMPATIBLE_ZONES, NO_EFFECTIVE_COST, COST_ZONE_MISMATCH }
    public record Zone(UUID id, String name, TemperatureClass temperatureClass, boolean active, long version, Instant createdAt, Instant updatedAt) { }
    public record ZonePage(List<Zone> items, boolean hasMore, boolean canManage) { }
    public record Lane(UUID id, UUID originStoreId, String originStoreName, boolean originStoreActive,
                       UUID destinationStoreId, String destinationStoreName, boolean destinationStoreActive,
                       UUID originZoneId, UUID destinationZoneId, boolean originZoneActive, boolean destinationZoneActive,
                       TemperatureClass originTemperature, TemperatureClass destinationTemperature,
                       boolean active, long version, long latestRevision, Instant latestEffectiveAt, Instant createdAt, Instant updatedAt) { }
    public record Revision(UUID id, long revision, String costAmount, String currencyCode, int durationMinutes,
                           Instant effectiveAt, Instant validUntil, Instant recordedAt, UUID recordedByMembershipId,
                           UUID originZoneId, UUID destinationZoneId, TemperatureClass temperatureClass) { }
    public record LaneView(Lane lane, Status status, List<Missing> missing, Revision currentCost, Instant asOf) { }
    public record LanePage(List<LaneView> items, boolean hasMore, Instant asOf) { }
    public record RevisionPage(List<Revision> items, boolean hasMore) { }
}
