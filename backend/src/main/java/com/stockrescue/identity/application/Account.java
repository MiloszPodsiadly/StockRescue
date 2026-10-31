package com.stockrescue.identity.application;

import java.time.Instant;
import java.util.UUID;

public record Account(UUID id, String email, Instant createdAt) { }
