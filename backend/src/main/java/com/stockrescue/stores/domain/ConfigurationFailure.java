package com.stockrescue.stores.domain;

public class ConfigurationFailure extends RuntimeException {
    public enum Kind { INVALID, CONFLICT, INACTIVE, INCOMPATIBLE_ZONES }
    private final Kind kind;
    public ConfigurationFailure(Kind kind) { this.kind = kind; }
    public Kind kind() { return kind; }
}
