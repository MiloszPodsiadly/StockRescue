package com.stockrescue.inventory.domain;

public enum InventoryOperation {
    RECEIVE, RESERVE, RELEASE, ADJUST, WRITE_OFF, QUARANTINE, HOLD, RELEASE_HOLD,
    SALE, VERIFY_ZERO, RECONCILE, REMEDIATE
}
