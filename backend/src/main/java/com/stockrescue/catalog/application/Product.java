package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.UUID;

public record Product(UUID id, ProductData data, boolean active, long version,
                      Instant createdAt, Instant updatedAt, Instant archivedAt) { }
