package com.stockrescue.inventory.messaging;

import java.util.Set;

public final class InventoryEventContractException extends RuntimeException {
    private static final Set<String> PERMANENT_CONTRACT_FAILURES=Set.of("MALFORMED_EVENT","UNSUPPORTED_SCHEMA_VERSION",
            "OUTBOX_CONTRACT_INVALID","TENANT_OR_AGGREGATE_MISMATCH","UNKNOWN_EVENT","OUTBOX_EVENT_MISMATCH",
            "DUPLICATE_PAYLOAD_MISMATCH");

    private final String code;
    public InventoryEventContractException(String code) { super(code); this.code=code; }
    public String code() { return code; }
    public boolean isPermanentContractFailure() { return PERMANENT_CONTRACT_FAILURES.contains(code); }
}
