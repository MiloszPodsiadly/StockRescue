package com.stockrescue.inventory.domain;

public class InventoryFailure extends RuntimeException {
    public enum Kind { INVALID_REQUEST, CONFLICT, INSUFFICIENT_STOCK, INACTIVE, IDEMPOTENCY_CONFLICT,
        DOCUMENT_ALREADY_RECEIVED, LOT_NOT_ELIGIBLE, ALLOCATION_UNAVAILABLE, POSITION_FROZEN, NOT_INITIALIZED }
    private final Kind kind;
    public InventoryFailure(Kind kind) { super(kind.name()); this.kind=kind; }
    public Kind kind() { return kind; }
}
