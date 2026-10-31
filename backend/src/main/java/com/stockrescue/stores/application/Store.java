package com.stockrescue.stores.application;

import java.time.Instant;
import java.util.UUID;
import com.stockrescue.stores.domain.StoreData;

public record Store(UUID id, StoreData data, boolean active, long version, Instant createdAt, Instant updatedAt) { }
