package com.stockrescue.inventory.messaging;

public final class InventoryEventContractException extends RuntimeException {
    private final String code;
    public InventoryEventContractException(String code) { super(code); this.code=code; }
    public String code() { return code; }
}
