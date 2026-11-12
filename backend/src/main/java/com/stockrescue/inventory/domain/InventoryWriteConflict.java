package com.stockrescue.inventory.domain;

public class InventoryWriteConflict extends InventoryFailure {
    public InventoryWriteConflict() { super(Kind.CONFLICT); }
}
