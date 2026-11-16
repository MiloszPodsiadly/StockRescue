package com.stockrescue.inventory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.inventory.application.InventoryCommandIntentService.InspectionStatus;
import com.stockrescue.inventory.persistence.InventoryCommandIntentRepository;
import com.stockrescue.inventory.persistence.InventoryCommandIntentRepository.Intent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InventoryCommandIntentStatusTest {
    private static final UUID ACTOR=UUID.randomUUID();
    private static final UUID ORGANIZATION=UUID.randomUUID();
    private static final UUID STORE=UUID.randomUUID();

    @Test void durableStatusIsConflictWhenExecutionMarkerAndReceiptEvidenceDisagree() {
        var repository=mock(InventoryCommandIntentRepository.class);
        var inventory=mock(InventoryService.class);
        var access=mock(AccessPolicy.class);
        var executedWithoutReceipt=intent(UUID.randomUUID(),Instant.now());
        var receiptWithoutExecutionMarker=intent(UUID.randomUUID(),null);
        when(repository.byId(eq(ORGANIZATION),eq(STORE),eq(ACTOR),eq(executedWithoutReceipt.id()),eq(false)))
                .thenReturn(Optional.of(executedWithoutReceipt));
        when(repository.byId(eq(ORGANIZATION),eq(STORE),eq(ACTOR),eq(receiptWithoutExecutionMarker.id()),eq(false)))
                .thenReturn(Optional.of(receiptWithoutExecutionMarker));
        when(repository.hasCommittedCommand(executedWithoutReceipt)).thenReturn(false);
        when(repository.hasCommittedCommand(receiptWithoutExecutionMarker)).thenReturn(true);
        var service=new InventoryCommandIntentService(access,inventory,repository,new ObjectMapper(),Clock.systemUTC());

        var first=service.status(ACTOR,ORGANIZATION,STORE,executedWithoutReceipt.id());
        var second=service.status(ACTOR,ORGANIZATION,STORE,receiptWithoutExecutionMarker.id());

        assertThat(first.executionStatus()).isEqualTo(InspectionStatus.CONFLICT);
        assertThat(second.executionStatus()).isEqualTo(InspectionStatus.CONFLICT);
        assertThat(first.committedOutcome()).isNull();
        assertThat(second.committedOutcome()).isNull();
        verifyNoInteractions(inventory);
    }

    private static Intent intent(UUID id,Instant executedAt) {
        return new Intent(id,ORGANIZATION,STORE,ACTOR,UUID.randomUUID(),"RESERVE","position:"+UUID.randomUUID(),
                UUID.randomUUID(),new byte[32],"{}","PENDING",Instant.now(),executedAt,null);
    }
}
