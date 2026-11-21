package com.stockrescue.transfers.messaging;

public final class TransferEventContractException extends RuntimeException {
    private final String code;
    public TransferEventContractException(String code) { super(code);this.code=code; }
    public String code() { return code; }
}
