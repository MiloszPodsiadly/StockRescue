package com.stockrescue.stores.domain;

public class StoreFailure extends RuntimeException {
    public enum Kind { INVALID_INPUT, CONFLICT, INACTIVE }
    private final Kind kind;
    public StoreFailure(Kind kind) { this.kind = kind; }
    public Kind kind() { return kind; }
}
