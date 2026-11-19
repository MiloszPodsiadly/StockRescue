package com.stockrescue.transfers.domain;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferQuantitiesTest {
    @Test
    void dispatchReceiptAndLossPreserveLineConservation() {
        var journey = TransferQuantities.undispatched()
                .dispatch(20)
                .receive(18)
                .confirmLoss(2);

        assertThat(journey).isEqualTo(new TransferQuantities(20, 18, 2, 0, 0));
        assertThat(journey.physicallyReceived() + journey.confirmedLoss()
                + journey.physicallyReturned() + journey.outstanding()).isEqualTo(journey.dispatched());
    }

    @Test
    void confirmedPhysicalReturnSettlesTransitWithoutCountingAReceipt() {
        var journey = TransferQuantities.undispatched().dispatch(20).receive(18).confirmReturn(2);

        assertThat(journey).isEqualTo(new TransferQuantities(20, 18, 0, 2, 0));
    }

    @Test
    void rejectsNegativeOrUnbalancedLineQuantities() {
        var invalidStates = List.of(
                new long[] {-1, 0, 0, 0, 0},
                new long[] {4, 1, 0, 0, 2},
                new long[] {2, 1, 1, 1, 0});

        for (var state : invalidStates) {
            assertThatThrownBy(() -> new TransferQuantities(state[0], state[1], state[2], state[3], state[4]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void refusesDuplicateDispatchAndSettlementBeyondOutstanding() {
        var dispatched = TransferQuantities.undispatched().dispatch(2);

        assertThatThrownBy(() -> dispatched.dispatch(2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> dispatched.receive(3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatched.confirmLoss(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
