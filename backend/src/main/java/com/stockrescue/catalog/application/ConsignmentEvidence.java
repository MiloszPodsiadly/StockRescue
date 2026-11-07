package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.UUID;

public record ConsignmentEvidence(UUID id,UUID lotId,SourceEvidenceData data,boolean active,long version,
                                  Instant createdAt,Instant updatedAt,Instant archivedAt) { }
