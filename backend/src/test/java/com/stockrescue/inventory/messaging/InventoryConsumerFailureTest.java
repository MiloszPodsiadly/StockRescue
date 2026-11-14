package com.stockrescue.inventory.messaging;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryConsumerFailureTest {
    @Test
    void classifiesKnownInfrastructureFailuresAsRetryable() {
        var connectionFailure=new SQLException("connection unavailable","08006");
        assertThat(InventoryConsumerFailure.categoryOf(connectionFailure))
                .isEqualTo(InventoryConsumerFailure.Category.TRANSIENT_INFRA);
        assertThat(InventoryConsumerFailure.classify(new IllegalStateException("wrapped",connectionFailure)).category())
                .isEqualTo(InventoryConsumerFailure.Category.TRANSIENT_INFRA);
    }

    @Test
    void keepsDeterministicApplicationFaultsOutOfTheTransientInfrastructureClass() {
        assertThat(InventoryConsumerFailure.categoryOf(new SQLException("constraint failure","23514")))
                .isEqualTo(InventoryConsumerFailure.Category.NONRETRYABLE_APP_UNKNOWN);
        assertThat(InventoryConsumerFailure.categoryOf(new IllegalStateException("projection invariant failed")))
                .isEqualTo(InventoryConsumerFailure.Category.NONRETRYABLE_APP_UNKNOWN);
    }

    @Test
    void recognizesOnlyTheExplicitPermanentEventContractFailuresForDurableParking() {
        assertThat(InventoryConsumerFailure.categoryOf(new InventoryEventContractException("MALFORMED_EVENT")))
                .isEqualTo(InventoryConsumerFailure.Category.PERMANENT_EVENT_DATA);
        assertThat(InventoryConsumerFailure.categoryOf(new InventoryEventContractException("UNRECOGNIZED_INVARIANT")))
                .isEqualTo(InventoryConsumerFailure.Category.NONRETRYABLE_APP_UNKNOWN);
    }
}
