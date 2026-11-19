package com.stockrescue.transfers.domain;

public final class TransferWriteConflict extends RuntimeException {
    public TransferWriteConflict() {
        super("Transfer state changed; reload and review the current transfer");
    }
}
