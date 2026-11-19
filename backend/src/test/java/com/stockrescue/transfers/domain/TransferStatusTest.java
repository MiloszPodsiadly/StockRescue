package com.stockrescue.transfers.domain;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferStatusTest {
    private static final Map<TransferStatus, Set<TransferStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(TransferStatus.REQUESTED, Set.of(TransferStatus.APPROVED, TransferStatus.REJECTED, TransferStatus.CANCELLED)),
            Map.entry(TransferStatus.REJECTED, Set.of()),
            Map.entry(TransferStatus.CANCELLED, Set.of()),
            Map.entry(TransferStatus.APPROVED, Set.of(TransferStatus.PICKING, TransferStatus.CANCELLED)),
            Map.entry(TransferStatus.PICKING, Set.of(TransferStatus.PICKING, TransferStatus.PICKED, TransferStatus.CANCELLED)),
            Map.entry(TransferStatus.PICKED, Set.of(TransferStatus.IN_TRANSIT, TransferStatus.CANCELLED)),
            Map.entry(TransferStatus.IN_TRANSIT, Set.of(TransferStatus.PARTIALLY_RECEIVED, TransferStatus.RECEIVED,
                    TransferStatus.RECONCILIATION_REQUIRED)),
            Map.entry(TransferStatus.PARTIALLY_RECEIVED, Set.of(TransferStatus.RECEIVED,
                    TransferStatus.RECONCILIATION_REQUIRED)),
            Map.entry(TransferStatus.RECONCILIATION_REQUIRED, Set.of(TransferStatus.IN_TRANSIT,
                    TransferStatus.PARTIALLY_RECEIVED, TransferStatus.RECEIVED, TransferStatus.CLOSED_WITH_DISCREPANCY)),
            Map.entry(TransferStatus.RECEIVED, Set.of()),
            Map.entry(TransferStatus.CLOSED_WITH_DISCREPANCY, Set.of()));

    @Test
    void permitsOnlyDocumentedLifecycleEdges() {
        for (var current : TransferStatus.values()) {
            for (var next : TransferStatus.values()) {
                assertThat(current.canTransitionTo(next))
                        .as("%s -> %s", current, next)
                        .isEqualTo(TRANSITIONS.get(current).contains(next));
            }
        }
    }

    @Test
    void marksOnlySettledOrAbandonedOrdersTerminal() {
        for (var status : TransferStatus.values()) {
            assertThat(status.terminal()).isEqualTo(Set.of(TransferStatus.REJECTED, TransferStatus.CANCELLED,
                    TransferStatus.RECEIVED, TransferStatus.CLOSED_WITH_DISCREPANCY).contains(status));
        }
    }
}
