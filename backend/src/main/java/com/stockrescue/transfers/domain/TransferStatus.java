package com.stockrescue.transfers.domain;

/**
 * Lifecycle graph only; commands still enforce actor, evidence and quantity guards.
 */
public enum TransferStatus {
    REQUESTED,
    REJECTED,
    CANCELLED,
    APPROVED,
    PICKING,
    PICKED,
    IN_TRANSIT,
    PARTIALLY_RECEIVED,
    RECONCILIATION_REQUIRED,
    RECEIVED,
    CLOSED_WITH_DISCREPANCY;

    public boolean canTransitionTo(TransferStatus next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case REQUESTED -> next == APPROVED || next == REJECTED || next == CANCELLED;
            case APPROVED -> next == PICKING || next == CANCELLED;
            // Each whole-line physical pick advances the aggregate version while
            // the transfer remains PICKING until every line has evidence.
            case PICKING -> next == PICKING || next == PICKED || next == CANCELLED;
            case PICKED -> next == IN_TRANSIT || next == CANCELLED;
            case IN_TRANSIT -> next == PARTIALLY_RECEIVED || next == RECEIVED || next == RECONCILIATION_REQUIRED;
            case PARTIALLY_RECEIVED -> next == PARTIALLY_RECEIVED || next == RECEIVED || next == RECONCILIATION_REQUIRED;
            case RECONCILIATION_REQUIRED -> next == IN_TRANSIT || next == PARTIALLY_RECEIVED
                    || next == RECONCILIATION_REQUIRED || next == RECEIVED || next == CLOSED_WITH_DISCREPANCY;
            case REJECTED, CANCELLED, RECEIVED, CLOSED_WITH_DISCREPANCY -> false;
        };
    }

    public boolean terminal() {
        return switch (this) {
            case REJECTED, CANCELLED, RECEIVED, CLOSED_WITH_DISCREPANCY -> true;
            default -> false;
        };
    }
}
