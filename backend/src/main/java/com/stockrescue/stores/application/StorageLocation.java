package com.stockrescue.stores.application;

import java.time.Instant;
import java.util.UUID;
import com.stockrescue.stores.domain.LocationData;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;

public record StorageLocation(UUID id,UUID storeId,LocationData data,TemperatureClass temperatureClass,boolean active,
                              boolean storeActive,boolean zoneActive,long version,Instant createdAt,Instant updatedAt,Instant archivedAt) {
    public boolean usable() { return active && storeActive && zoneActive; }
}
