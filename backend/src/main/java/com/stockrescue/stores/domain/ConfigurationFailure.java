package com.stockrescue.stores.domain;

public class ConfigurationFailure extends RuntimeException {
    public enum Kind { INVALID, CONFLICT, INACTIVE, INCOMPATIBLE_ZONES, SCHEDULED_REVISIONS_PENDING, NO_FUTURE_REVISIONS }
    private final Kind kind;
    public ConfigurationFailure(Kind kind) { this.kind = kind; }
    public Kind kind() { return kind; }
}
