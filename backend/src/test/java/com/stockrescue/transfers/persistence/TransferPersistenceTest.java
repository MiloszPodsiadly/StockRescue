package com.stockrescue.transfers.persistence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.stockrescue.inventory.domain.InventoryOperation;
import com.stockrescue.inventory.domain.StockBuckets.Delta;
import com.stockrescue.inventory.domain.StockPosition.Trust;
import com.stockrescue.inventory.persistence.InventoryRepository;
import com.stockrescue.inventory.persistence.InventoryRepository.CommandReceipt;
import com.stockrescue.transfers.domain.TransferMovementType;
import com.stockrescue.transfers.domain.TransferQuantities;
import com.stockrescue.transfers.domain.TransferStatus;
import com.stockrescue.transfers.domain.TransferWriteConflict;
import com.stockrescue.transfers.persistence.TransferOutboxRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferPersistenceTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-alpine"))
            .withDatabaseName("stockrescue_test")
            .withUsername("test_admin")
            .withPassword("test-admin-password")
            .withInitScript("db/test-role.sql");
    private static DriverManagerDataSource source;
    private static JdbcClient jdbc;
    private static TransactionTemplate transactions;
    private static InventoryRepository inventory;
    private static TransferRepository transfers;
    private static TransferOutboxRepository transferOutbox;

    @BeforeAll
    static void start() {
        DATABASE.start();
        source = dataSource(DATABASE);
        jdbc = JdbcClient.create(source);
        transactions = new TransactionTemplate(new JdbcTransactionManager(source));
        inventory = new InventoryRepository(jdbc, new ObjectMapper());
        transfers = new TransferRepository(jdbc);
        transferOutbox = new TransferOutboxRepository(jdbc);
        Flyway.configure().dataSource(source).defaultSchema("public").cleanDisabled(true).load().migrate();
    }

    @AfterAll
    static void stop() {
        DATABASE.stop();
    }

    @Test
    void compositeTenantAndStoreConstraintsRejectForeignEndpointsAndSameStoreTransfers() {
        var fixture = fixture();
        rejectsSqlState("23514", () -> execute("""
                INSERT INTO stockrescue.transfer_order(id,org_id,source_store_id,destination_store_id,
                    created_by_user_id,created_by_membership_id)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), fixture.organizationId(), fixture.sourceStoreId(),
                fixture.sourceStoreId(), fixture.actorId(), fixture.membershipId()));
        rejectsSqlState("23503", () -> execute("""
                INSERT INTO stockrescue.transfer_order(id,org_id,source_store_id,destination_store_id,
                    created_by_user_id,created_by_membership_id)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), fixture.organizationId(), fixture.sourceStoreId(),
                fixture.otherSourceStoreId(), fixture.actorId(), fixture.membershipId()));
    }

    @Test
    void lineQuantitiesRejectNegativeAndOverflowingEaValues() {
        var fixture = fixture();
        var seed = inTransaction(() -> insertTransfer(fixture, List.of(
                line(fixture.sourcePositionId(), fixture.destinationLocationId(), 5, 0))));
        var secondPosition = fixture.secondSourcePositionId();
        rejectsSqlState("23514", () -> execute("""
                INSERT INTO stockrescue.transfer_line
                    (id,org_id,transfer_id,source_store_id,destination_store_id,line_number,
                     source_position_id,destination_location_id,requested_quantity,expected_source_version)
                VALUES (?,?,?,?,?,?,?,?,-1,0)
                """, UUID.randomUUID(), fixture.organizationId(), seed.transferId(), fixture.sourceStoreId(),
                fixture.destinationStoreId(), 2, secondPosition, fixture.destinationLocationId()));
        rejectsSqlState("22003", () -> execute("""
                INSERT INTO stockrescue.transfer_line
                    (id,org_id,transfer_id,source_store_id,destination_store_id,line_number,
                     source_position_id,destination_location_id,requested_quantity,expected_source_version)
                VALUES (?,?,?,?,?,?,?,?,?,0)
                """, UUID.randomUUID(), fixture.organizationId(), seed.transferId(), fixture.sourceStoreId(),
                fixture.destinationStoreId(), 2, secondPosition, fixture.destinationLocationId(),
                new BigDecimal("9223372036854775808")));
    }

    @Test
    void oneReservationAllocationCannotBelongToMultipleTransferLines() {
        var fixture = fixture();
        var reserved = seedDispatchedTransfer(fixture, 2);
        UUID allocationId = transfers.detail(fixture.organizationId(), reserved.transferId()).orElseThrow()
                .lines().getFirst().reservationAllocationId();
        var otherTransfer = inTransaction(() -> insertTransfer(fixture, List.of(
                line(fixture.sourcePositionId(), fixture.destinationLocationId(), 2, 0))));

        rejectsSqlState("23505", () -> execute("""
                UPDATE stockrescue.transfer_line
                SET reservation_allocation_id=?,reservation_bucket='RESERVED'
                WHERE org_id=? AND transfer_id=? AND id=?
                """, allocationId, fixture.organizationId(), otherTransfer.transferId(), otherTransfer.lineId()));
    }

    @Test
    void transferDetailPagingAndTenantIsolationReturnOnlyScopedLines() {
        var fixture = fixture();
        var current = inTransaction(() -> insertTransfer(fixture, List.of(
                line(fixture.sourcePositionId(), fixture.destinationLocationId(), 4, 0),
                line(fixture.secondSourcePositionId(), fixture.destinationLocationId(), 3, 0))));
        var foreign = inTransaction(() -> insertForeignTransfer(fixture));

        var detail = transfers.detail(fixture.organizationId(), current.transferId()).orElseThrow();
        assertThat(detail.lines()).hasSize(2);
        assertThat(detail.lines()).extracting(TransferRepository.TransferLine::sku).containsOnly("BREAD");
        assertThat(detail.transfer().lineCount()).isEqualTo(2);
        assertThat(detail.transfer().outstandingQuantity()).isZero();
        assertThat(transfers.detail(fixture.organizationId(), foreign.transferId())).isEmpty();

        var page = transfers.page(fixture.organizationId(), fixture.sourceStoreId(), null, 0, 1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().id()).isEqualTo(current.transferId());
        assertThat(page.hasMore()).isFalse();
        assertThatThrownBy(() -> transfers.page(fixture.organizationId(), null, null, 0, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void staleTransferAndCustodyVersionsCannotOverwriteCommittedState() {
        var fixture = fixture();
        var requested = inTransaction(() -> insertTransfer(fixture, List.of(
                line(fixture.sourcePositionId(), fixture.destinationLocationId(), 2, 0))));
        var approval = inTransaction(() -> {
            var command = recordTransferCommand(fixture, requested.transferId(), "APPROVE", null);
            return transfers.advanceStatus(fixture.organizationId(), requested.transferId(), 0,
                    TransferStatus.APPROVED, command, fixture.actorId(), fixture.membershipId(), "Approve request", "approval-evidence");
        });
        assertThat(approval).isEqualTo(1);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            var command = recordTransferCommand(fixture, requested.transferId(), "APPROVE", null);
            transfers.advanceStatus(fixture.organizationId(), requested.transferId(), 1,
                    TransferStatus.APPROVED, command, fixture.actorId(), fixture.membershipId(),
                    "Repeat approval", "approval-retry");
        })).isInstanceOf(TransferWriteConflict.class);
        rejectsSqlState("23514", () -> execute("""
                UPDATE stockrescue.transfer_order SET version=version+1
                WHERE org_id=? AND id=?
                """, fixture.organizationId(), requested.transferId()));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            var command = recordTransferCommand(fixture, requested.transferId(), "REJECT", null);
            transfers.advanceStatus(fixture.organizationId(), requested.transferId(), 0,
                    TransferStatus.REJECTED, command, fixture.actorId(), fixture.membershipId(), "Stale rejection", "review-evidence");
        })).isInstanceOf(TransferWriteConflict.class);
        assertThat(transfers.detail(fixture.organizationId(), requested.transferId()).orElseThrow().transfer().status())
                .isEqualTo(TransferStatus.APPROVED);

        var dispatched = seedDispatchedTransfer(fixture, 2);
        var loss = inTransaction(() -> recordTransferCommand(fixture, dispatched.transferId(), "CONFIRM_LOSS", null));
        inTransaction(() -> {
            recordDamageEvidence(fixture, dispatched, loss, 1);
            transfers.appendCustodyMovement(custody(fixture, dispatched, loss, 1, TransferMovementType.LOSS, 1, null));
            return null;
        });
        var retry = inTransaction(() -> recordTransferCommand(fixture, dispatched.transferId(), "CONFIRM_LOSS", null));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            recordDamageEvidence(fixture, dispatched, retry, 1);
            transfers.appendCustodyMovement(custody(fixture, dispatched, retry, 1, TransferMovementType.LOSS, 1, null));
        }))
                .isInstanceOf(TransferWriteConflict.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            var overdraw = recordTransferCommand(fixture, dispatched.transferId(), "CONFIRM_LOSS", null);
            recordDamageEvidence(fixture, dispatched, overdraw, 2);
            transfers.appendCustodyMovement(custody(fixture, dispatched, overdraw, 2,
                    TransferMovementType.LOSS, 2, null));
        })).isInstanceOf(TransferWriteConflict.class);
        var quantities = transfers.detail(fixture.organizationId(), dispatched.transferId()).orElseThrow()
                .lines().getFirst().quantities();
        assertThat(quantities).isEqualTo(new TransferQuantities(2, 0, 1, 0, 1));
    }

    @Test
    void concurrentSettlementsCannotConsumeOutstandingTwice() throws Exception {
        var fixture = fixture();
        var dispatched = seedDispatchedTransfer(fixture, 2);
        var firstCommand = inTransaction(() -> recordTransferCommand(fixture, dispatched.transferId(), "CONFIRM_LOSS", null));
        var secondCommand = inTransaction(() -> recordTransferCommand(fixture, dispatched.transferId(), "CONFIRM_LOSS", null));
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> settleConcurrently(fixture, dispatched, firstCommand, ready, release));
            var second = executor.submit(() -> settleConcurrently(fixture, dispatched, secondCommand, ready, release));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            release.countDown();
        }
        var quantities = transfers.detail(fixture.organizationId(), dispatched.transferId()).orElseThrow()
                .lines().getFirst().quantities();
        assertThat(quantities).isEqualTo(new TransferQuantities(2, 0, 2, 0, 0));
        assertThat(transfers.custodyTimeline(fixture.organizationId(), dispatched.transferId(), 0, 20).items())
                .filteredOn(movement -> movement.type() == TransferMovementType.LOSS).hasSize(1);
    }

    @Test
    void failedTransactionRollsBackCustodyMovementProjectionAndCommandReceipt() {
        var fixture = fixture();
        var dispatched = seedDispatchedTransfer(fixture, 3);
        var command = transferCommand(fixture, dispatched.transferId(), "CONFIRM_LOSS", null,
                fixture.organizationId(), fixture.membershipId());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            assertThat(transfers.recordCommand(command)).isTrue();
            recordDamageEvidence(fixture, dispatched, command.id(), 1);
            transfers.appendCustodyMovement(custody(fixture, dispatched, command.id(), 1,
                    TransferMovementType.LOSS, 1, null));
            throw new IllegalStateException("injected transaction failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("injected transaction failure");

        var quantities = transfers.detail(fixture.organizationId(), dispatched.transferId()).orElseThrow()
                .lines().getFirst().quantities();
        assertThat(quantities).isEqualTo(new TransferQuantities(3, 0, 0, 0, 3));
        assertThat(countByTransfer("transfer_custody_movement", fixture.organizationId(), dispatched.transferId()))
                .isEqualTo(1);
        assertThat(count("transfer_command_receipt", fixture.organizationId(), command.id())).isZero();
    }

    @Test
    void failedTransitionRollsBackItsOutboxEventAndDelivery() {
        var fixture=fixture();
        var requested=inTransaction(()->insertTransfer(fixture,List.of(
                line(fixture.sourcePositionId(),fixture.destinationLocationId(),1,0))));
        assertThat(countByTransfer("transfer_transition",fixture.organizationId(),requested.transferId())).isEqualTo(1);
        assertThat(countByTransfer("transfer_outbox_event",fixture.organizationId(),requested.transferId())).isEqualTo(1);
        assertThat(countByTransfer("transfer_outbox_delivery",fixture.organizationId(),requested.transferId())).isEqualTo(1);
        var command=transferCommand(fixture,requested.transferId(),"APPROVE",null,
                fixture.organizationId(),fixture.membershipId());

        assertThatThrownBy(()->transactions.executeWithoutResult(status->{
            assertThat(transfers.recordCommand(command)).isTrue();
            transfers.advanceStatus(fixture.organizationId(),requested.transferId(),0,TransferStatus.APPROVED,
                    command.id(),fixture.actorId(),fixture.membershipId(),"Approve then roll back","approval-evidence");
            throw new IllegalStateException("injected failure after event enqueue");
        })).isInstanceOf(IllegalStateException.class).hasMessage("injected failure after event enqueue");

        assertThat(transfers.detail(fixture.organizationId(),requested.transferId()).orElseThrow().transfer().status())
                .isEqualTo(TransferStatus.REQUESTED);
        assertThat(count("transfer_command_receipt",fixture.organizationId(),command.id())).isZero();
        assertThat(countByTransfer("transfer_transition",fixture.organizationId(),requested.transferId())).isEqualTo(1);
        assertThat(countByTransfer("transfer_outbox_event",fixture.organizationId(),requested.transferId())).isEqualTo(1);
        assertThat(countByTransfer("transfer_outbox_delivery",fixture.organizationId(),requested.transferId())).isEqualTo(1);
    }

    @Test
    void expiredTransferOutboxLeaseFencesThePreviousRelayOwner() {
        var fixture=fixture();
        var requested=inTransaction(()->insertTransfer(fixture,List.of(
                line(fixture.sourcePositionId(),fixture.destinationLocationId(),1,0))));
        UUID firstOwner=UUID.randomUUID(),nextOwner=UUID.randomUUID();
        var firstClaims=transferOutbox.claimDue(firstOwner,500,java.time.Duration.ofSeconds(20));
        var first=firstClaims.stream().filter(claim->claim.organizationId().equals(fixture.organizationId())
                &&claim.transferId().equals(requested.transferId())).findFirst().orElseThrow();
        firstClaims.stream().filter(claim->!claim.id().equals(first.id()))
                .forEach(claim->transferOutbox.scheduleRetry(claim,"BROKER_UNAVAILABLE",java.time.Duration.ofSeconds(30)));

        assertThat(jdbc.sql("UPDATE stockrescue.transfer_outbox_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id=:event")
                .param("event",first.id()).update()).isEqualTo(1);
        var nextClaims=transferOutbox.claimDue(nextOwner,500,java.time.Duration.ofSeconds(20));
        var current=nextClaims.stream().filter(claim->claim.id().equals(first.id())).findFirst().orElseThrow();
        nextClaims.stream().filter(claim->!claim.id().equals(current.id()))
                .forEach(claim->transferOutbox.scheduleRetry(claim,"BROKER_UNAVAILABLE",java.time.Duration.ofSeconds(30)));

        assertThat(current.claimVersion()).isEqualTo(first.claimVersion()+1);
        assertThat(transferOutbox.markPublished(first)).isFalse();
        assertThat(transferOutbox.scheduleRetry(first,"BROKER_UNAVAILABLE",java.time.Duration.ofSeconds(1))).isFalse();
        assertThat(transferOutbox.park(first,"OUTBOX_CONTRACT_INVALID")).isFalse();
        assertThat(transferOutbox.markPublished(current)).isTrue();
    }

    @Test
    void replayAndOutOfOrderCustodyEvidenceCannotCorruptOutstanding() {
        var fixture = fixture();
        var dispatched = seedDispatchedTransfer(fixture, 2);
        var command = inTransaction(() -> recordTransferCommand(fixture, dispatched.transferId(), "CONFIRM_LOSS", null));
        inTransaction(() -> {
            recordDamageEvidence(fixture, dispatched, command, 1);
            transfers.appendCustodyMovement(custody(fixture, dispatched, command, 1,
                    TransferMovementType.LOSS, 1, null));
            return null;
        });
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> transfers.appendCustodyMovement(
                custody(fixture, dispatched, command, 1, TransferMovementType.LOSS, 1, null))))
                .isInstanceOf(TransferWriteConflict.class);

        var later = inTransaction(() -> recordTransferCommand(fixture, dispatched.transferId(), "CONFIRM_LOSS", null));
        inTransaction(() -> {
            recordDamageEvidence(fixture, dispatched, later, 1);
            return null;
        });
        rejectsSqlState("23503", () -> execute("""
                INSERT INTO stockrescue.transfer_custody_movement
                    (id,org_id,transfer_id,line_id,version,movement_type,command_id,actor_user_id,membership_id,
                     quantity,outstanding_before,outstanding_after,reason,evidence_reference)
                VALUES (?,?,?, ?,4,'LOSS',?,?,?,1,1,0,'Out of order evidence','manual-check')
                """, UUID.randomUUID(), fixture.organizationId(), dispatched.transferId(), dispatched.lineId(),
                later, fixture.actorId(), fixture.membershipId()));
        var quantities = transfers.detail(fixture.organizationId(), dispatched.transferId()).orElseThrow()
                .lines().getFirst().quantities();
        assertThat(quantities).isEqualTo(new TransferQuantities(2, 0, 1, 0, 1));
    }

    @Test
    void agingTimelineDiscrepancyAndImmutableEvidenceReadModelsAreBackedByPostgres() {
        var fixture = fixture();
        var dispatched = seedDispatchedTransfer(fixture, 5);
        var discrepancyCommand = inTransaction(() -> recordTransferCommand(fixture, dispatched.transferId(), "OPEN_DISCREPANCY", null));
        inTransaction(() -> {
            execute("""
                INSERT INTO stockrescue.transfer_reconciliation_evidence
                    (id,org_id,transfer_id,line_id,command_id,actor_user_id,membership_id,evidence_type,
                     expected_state,observed_state,reason,evidence_reference)
                VALUES (?,?,?,?,?,?,?,'DISCREPANCY','{"outstanding":5}','{"observed":4}',
                        'Count differs from dispatch document','count-sheet-1')
                """, UUID.randomUUID(), fixture.organizationId(), dispatched.transferId(), dispatched.lineId(),
                discrepancyCommand, fixture.actorId(), fixture.membershipId());
            return null;
        });

        var aging = transfers.inTransitAging(fixture.organizationId(), fixture.sourceStoreId(), 0, 10);
        assertThat(aging.items()).hasSize(1);
        assertThat(aging.items().getFirst().outstanding()).isEqualTo(5);
        assertThat(aging.items().getFirst().ageSeconds()).isGreaterThanOrEqualTo(0);
        assertThat(aging.items().getFirst().dispatchedAt()).isNotNull();
        assertThat(transfers.custodyTimeline(fixture.organizationId(), dispatched.transferId(), 0, 10).items())
                .extracting(TransferRepository.CustodyMovement::type).containsExactly(TransferMovementType.DISPATCH);
        assertThat(transfers.discrepancies(fixture.organizationId(), fixture.destinationStoreId(), 0, 10).items())
                .extracting(TransferRepository.Discrepancy::evidenceReference).containsExactly("count-sheet-1");

        rejectsSqlState("55000", () -> execute("""
                UPDATE stockrescue.transfer_custody_movement SET reason='edited'
                WHERE transfer_id=? AND line_id=?
                """, dispatched.transferId(), dispatched.lineId()));
        rejectsSqlState("55000", () -> execute("DELETE FROM stockrescue.transfer_transition WHERE transfer_id=?", dispatched.transferId()));
        rejectsSqlState("55000", () -> execute("""
                UPDATE stockrescue.transfer_reconciliation_evidence SET reason='edited' WHERE command_id=?
                """, discrepancyCommand));
    }

    @Test
    void transferQueueAndInTransitReadModelsReturnStablePagesBeyondOneHundredRows() {
        var fixture = fixture();
        for (int index = 0; index < 101; index++) seedDispatchedTransfer(fixture, 1);

        var queueFirst = transfers.page(fixture.organizationId(), fixture.sourceStoreId(), null, 0, 50);
        var queueSecond = transfers.page(fixture.organizationId(), fixture.sourceStoreId(), null, 50, 50);
        var queueThird = transfers.page(fixture.organizationId(), fixture.sourceStoreId(), null, 100, 50);
        assertThat(queueFirst.items()).hasSize(50);
        assertThat(queueFirst.hasMore()).isTrue();
        assertThat(queueSecond.items()).hasSize(50);
        assertThat(queueSecond.hasMore()).isTrue();
        assertThat(queueThird.items()).hasSize(1);
        assertThat(queueThird.hasMore()).isFalse();
        var transferIds = java.util.stream.Stream.of(queueFirst, queueSecond, queueThird)
                .flatMap(page -> page.items().stream()).map(TransferRepository.TransferSummary::id).toList();
        assertThat(transferIds).hasSize(101).doesNotHaveDuplicates();

        var transitFirst = transfers.inTransitAging(fixture.organizationId(), fixture.sourceStoreId(), 0, 50);
        var transitSecond = transfers.inTransitAging(fixture.organizationId(), fixture.sourceStoreId(), 50, 50);
        var transitThird = transfers.inTransitAging(fixture.organizationId(), fixture.sourceStoreId(), 100, 50);
        assertThat(transitFirst.items()).hasSize(50);
        assertThat(transitFirst.hasMore()).isTrue();
        assertThat(transitSecond.items()).hasSize(50);
        assertThat(transitSecond.hasMore()).isTrue();
        assertThat(transitThird.items()).hasSize(1);
        assertThat(transitThird.hasMore()).isFalse();
        var lineKeys = java.util.stream.Stream.of(transitFirst, transitSecond, transitThird)
                .flatMap(page -> page.items().stream()).map(item -> item.transferId() + ":" + item.lineId()).toList();
        assertThat(lineKeys).hasSize(101).doesNotHaveDuplicates();
    }

    private static boolean settleConcurrently(Fixture fixture, TransferSeed dispatched, UUID commandId,
            CountDownLatch ready, CountDownLatch release) throws Exception {
        ready.countDown();
        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrency gate timed out");
        try {
            transactions.executeWithoutResult(status -> {
                recordDamageEvidence(fixture, dispatched, commandId, 2);
                transfers.appendCustodyMovement(custody(fixture, dispatched, commandId, 1,
                        TransferMovementType.LOSS, 2, null));
            });
            return true;
        } catch (TransferWriteConflict conflict) {
            return false;
        }
    }

    private static TransferSeed seedDispatchedTransfer(Fixture fixture, long quantity) {
        return inTransaction(() -> {
            var adjusted = stockCommand(fixture, "ADJUST", InventoryOperation.ADJUST, "seed-stock");
            var before = inventory.position(fixture.organizationId(), fixture.sourceStoreId(), fixture.sourcePositionId(), true).orElseThrow();
            inventory.postLine(fixture.organizationId(), fixture.sourceStoreId(), adjusted.movementId(),
                    fixture.sourcePositionId(), before.version(), new Delta(quantity, 0, 0, 0), Trust.UNVERIFIED);

            var transfer = insertTransfer(fixture, List.of(line(fixture.sourcePositionId(),
                    fixture.destinationLocationId(), quantity, before.version() + 1)));
            var reserved = stockCommand(fixture, "TRANSFER_RESERVE", null, "reserve-stock");
            var sourceBeforeReserve = inventory.position(fixture.organizationId(), fixture.sourceStoreId(),
                    fixture.sourcePositionId(), true).orElseThrow();
            inventory.postLine(fixture.organizationId(), fixture.sourceStoreId(), reserved.movementId(),
                    fixture.sourcePositionId(), sourceBeforeReserve.version(), new Delta(0, quantity, 0, 0), Trust.UNVERIFIED);
            UUID allocationId = UUID.randomUUID();
            inventory.openAllocation(fixture.organizationId(), fixture.sourceStoreId(), fixture.sourcePositionId(),
                    allocationId, "RESERVED", fixture.actorId(), fixture.membershipId(), quantity, reserved.movementId());

            var approve = recordTransferCommand(fixture, transfer.transferId(), "APPROVE", reserved);
            transfers.advanceStatus(fixture.organizationId(), transfer.transferId(), 0, TransferStatus.APPROVED,
                    approve, fixture.actorId(), fixture.membershipId(), "Reserve transfer source", "approval-evidence");
            execute("""
                    UPDATE stockrescue.transfer_line SET reservation_allocation_id=?,reservation_bucket='RESERVED'
                    WHERE org_id=? AND transfer_id=? AND id=?
                    """, allocationId, fixture.organizationId(), transfer.transferId(), transfer.lineId());

            var startPick = recordTransferCommand(fixture, transfer.transferId(), "START_PICK", null);
            transfers.advanceStatus(fixture.organizationId(), transfer.transferId(), 1, TransferStatus.PICKING,
                    startPick, fixture.actorId(), fixture.membershipId(), "Start source pick", "pick-list");
            var pick = recordTransferCommand(fixture, transfer.transferId(), "PICK", null);
            var pickedVersion = inventory.position(fixture.organizationId(), fixture.sourceStoreId(),
                    fixture.sourcePositionId(), true).orElseThrow().version();
            insertPhysicalDocument(fixture, transfer, "PICK", "SOURCE", "pick-doc-" + transfer.transferId(), "PICKED", quantity,
                    pick, pickedVersion);
            transfers.advanceStatus(fixture.organizationId(), transfer.transferId(), 2, TransferStatus.PICKED,
                    pick, fixture.actorId(), fixture.membershipId(), "Confirm full line pick", "pick-doc");

            var out = stockCommand(fixture, "TRANSFER_OUT", null, "dispatch-stock");
            var dispatch = recordTransferCommand(fixture, transfer.transferId(), "DISPATCH", out);
            var sourceBeforeDispatch = inventory.position(fixture.organizationId(), fixture.sourceStoreId(),
                    fixture.sourcePositionId(), true).orElseThrow();
            inventory.postLine(fixture.organizationId(), fixture.sourceStoreId(), out.movementId(),
                    fixture.sourcePositionId(), sourceBeforeDispatch.version(), new Delta(-quantity, -quantity, 0, 0), Trust.UNVERIFIED);
            var allocation = inventory.allocation(fixture.organizationId(), fixture.sourceStoreId(),
                    fixture.sourcePositionId(), allocationId).orElseThrow();
            inventory.reduceAllocation(fixture.organizationId(), fixture.sourceStoreId(), allocation,
                    quantity, "CONSUME", out.movementId());
            transfers.appendCustodyMovement(custody(fixture, transfer, dispatch, 0,
                    TransferMovementType.DISPATCH, quantity, new StockLink(fixture.sourceStoreId(),
                            out.movementId(), fixture.sourcePositionId())));
            insertPhysicalDocument(fixture, transfer, "DISPATCH", "SOURCE", "dispatch-doc-" + transfer.transferId(), "COUNTED", quantity,
                    dispatch, null);
            transfers.advanceStatus(fixture.organizationId(), transfer.transferId(), 3, TransferStatus.IN_TRANSIT,
                    dispatch, fixture.actorId(), fixture.membershipId(), "Dispatch full transfer", "dispatch-doc");
            return transfer;
        });
    }

    private static void insertPhysicalDocument(Fixture fixture, TransferSeed transfer, String type,
            String side, String reference, String disposition, long quantity, UUID commandId, Long sourcePositionVersion) {
        UUID id = UUID.randomUUID();
        UUID documentStore = side.equals("SOURCE") ? fixture.sourceStoreId() : fixture.destinationStoreId();
        execute("""
                INSERT INTO stockrescue.transfer_physical_document
                    (id,org_id,transfer_id,source_store_id,destination_store_id,document_store_id,command_id,store_side,
                     document_type,document_reference,reason,evidence_reference,recorded_by_user_id,membership_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,'Manual transfer evidence',?,?,?)
                """, id, fixture.organizationId(), transfer.transferId(), fixture.sourceStoreId(),
                fixture.destinationStoreId(), documentStore, commandId, side, type, reference, reference,
                fixture.actorId(), fixture.membershipId());
        execute("""
                INSERT INTO stockrescue.transfer_physical_document_line
                    (org_id,transfer_id,document_id,line_id,document_type,line_number,document_line_reference,quantity,disposition,
                     observed_sku,observed_lot_code,source_position_version)
                VALUES (?,?,?,?,?,1,?,?,?,'BREAD','LOT-A',?)
                """, fixture.organizationId(), transfer.transferId(), id, transfer.lineId(), type,
                reference, quantity, disposition, sourcePositionVersion);
    }

    private static StockPosting stockCommand(Fixture fixture, String operation,
            InventoryOperation inventoryOperation, String evidence) {
        UUID commandId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        if (inventoryOperation != null) {
            var command = new CommandReceipt(commandId, fixture.organizationId(), fixture.sourceStoreId(),
                    fixture.actorId(), fixture.membershipId(), inventoryOperation, requestId, new byte[32], "{\"ok\":true}");
            assertThat(inventory.recordCommand(command)).isTrue();
            inventory.recordMovement(movementId, command, "Seed physical stock", evidence);
        } else {
            execute("""
                    INSERT INTO stockrescue.inventory_command_receipt
                        (id,org_id,store_id,actor_user_id,membership_id,operation,request_id,payload_digest,outcome)
                    VALUES (?,?,?,?,?,?,?,?,'{"ok":true}')
                    """, commandId, fixture.organizationId(), fixture.sourceStoreId(), fixture.actorId(),
                    fixture.membershipId(), operation, requestId, new byte[32]);
            execute("""
                    INSERT INTO stockrescue.stock_movement(id,org_id,store_id,command_id,operation,reason,evidence_reference)
                    VALUES (?,?,?,?,?,'Transfer test movement',?)
                    """, movementId, fixture.organizationId(), fixture.sourceStoreId(), commandId, operation, evidence);
        }
        return new StockPosting(commandId, movementId, requestId, operation);
    }

    private static TransferSeed insertTransfer(Fixture fixture, List<LineSeed> lines) {
        UUID transferId = UUID.randomUUID();
        execute("""
                INSERT INTO stockrescue.transfer_order
                    (id,org_id,source_store_id,destination_store_id,created_by_user_id,created_by_membership_id)
                VALUES (?,?,?,?,?,?)
                """, transferId, fixture.organizationId(), fixture.sourceStoreId(), fixture.destinationStoreId(),
                fixture.actorId(), fixture.membershipId());
        UUID createCommand = recordTransferCommand(fixture, transferId, "CREATE", null);
        execute("""
                INSERT INTO stockrescue.transfer_transition
                    (org_id,transfer_id,version,status_before,status_after,command_id,actor_user_id,membership_id,reason,evidence_reference)
                VALUES (?,?,0,NULL,'REQUESTED',?,?,?,'Create transfer request','request-evidence')
                """, fixture.organizationId(), transferId, createCommand, fixture.actorId(), fixture.membershipId());
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            execute("""
                    INSERT INTO stockrescue.transfer_line
                        (id,org_id,transfer_id,source_store_id,destination_store_id,line_number,source_position_id,
                         destination_location_id,requested_quantity,expected_source_version)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, line.id(), fixture.organizationId(), transferId, fixture.sourceStoreId(),
                    fixture.destinationStoreId(), index + 1, line.sourcePositionId(), line.destinationLocationId(),
                    line.quantity(), line.expectedSourceVersion());
            execute("""
                    INSERT INTO stockrescue.transfer_custody_account(org_id,transfer_id,line_id)
                    VALUES (?,?,?)
                    """, fixture.organizationId(), transferId, line.id());
        }
        return new TransferSeed(transferId, createCommand, lines.getFirst().id(), lines);
    }

    private static TransferSeed insertForeignTransfer(Fixture fixture) {
        return inTransaction(() -> {
            UUID transferId = UUID.randomUUID();
            execute("""
                    INSERT INTO stockrescue.transfer_order
                        (id,org_id,source_store_id,destination_store_id,created_by_user_id,created_by_membership_id)
                    VALUES (?,?,?,?,?,?)
                    """, transferId, fixture.otherOrganizationId(), fixture.otherSourceStoreId(),
                    fixture.otherDestinationStoreId(), fixture.actorId(), fixture.otherMembershipId());
            UUID createCommand = recordTransferCommand(fixture, transferId, "CREATE", null,
                    fixture.otherOrganizationId(), fixture.otherMembershipId());
            execute("""
                    INSERT INTO stockrescue.transfer_transition
                        (org_id,transfer_id,version,status_before,status_after,command_id,actor_user_id,membership_id,reason,evidence_reference)
                    VALUES (?,?,0,NULL,'REQUESTED',?,?,?,'Create transfer request','request-evidence')
                    """, fixture.otherOrganizationId(), transferId, createCommand, fixture.actorId(), fixture.otherMembershipId());
            var line = line(fixture.otherSourcePositionId(), fixture.otherDestinationLocationId(), 1, 0);
            execute("""
                    INSERT INTO stockrescue.transfer_line
                        (id,org_id,transfer_id,source_store_id,destination_store_id,line_number,source_position_id,
                         destination_location_id,requested_quantity,expected_source_version)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, line.id(), fixture.otherOrganizationId(), transferId, fixture.otherSourceStoreId(),
                    fixture.otherDestinationStoreId(), 1, line.sourcePositionId(), line.destinationLocationId(),
                    line.quantity(), line.expectedSourceVersion());
            execute("INSERT INTO stockrescue.transfer_custody_account(org_id,transfer_id,line_id) VALUES (?,?,?)",
                    fixture.otherOrganizationId(), transferId, line.id());
            return new TransferSeed(transferId, createCommand, line.id(), List.of(line));
        });
    }

    private static UUID recordTransferCommand(Fixture fixture, UUID transferId, String operation, StockPosting stock) {
        return recordTransferCommand(fixture, transferId, operation, stock, fixture.organizationId(),
                fixture.membershipId());
    }

    private static UUID recordTransferCommand(Fixture fixture, UUID transferId, String operation,
            StockPosting stock, UUID organizationId, UUID membershipId) {
        var command = transferCommand(fixture, transferId, operation, stock, organizationId, membershipId);
        if (!transfers.recordCommand(command)) throw new IllegalStateException("Unexpected transfer command replay");
        return command.id();
    }

    private static TransferRepository.TransferCommandReceipt transferCommand(Fixture fixture, UUID transferId,
            String operation, StockPosting stock, UUID organizationId, UUID membershipId) {
        UUID id = UUID.randomUUID();
        return new TransferRepository.TransferCommandReceipt(id, organizationId, transferId,
                fixture.actorId(), membershipId, operation, UUID.randomUUID(), new byte[32], "{\"accepted\":true}",
                null,
                stock == null ? null : fixture.sourceStoreId(), stock == null ? null : stock.commandId(),
                stock == null ? null : stock.operation());
    }

    private static void recordDamageEvidence(Fixture fixture,TransferSeed transfer,UUID commandId,long quantity) {
        transfers.lockTransferForCommand(fixture.organizationId(),transfer.transferId());
        var identity=transfers.identity(fixture.organizationId(),transfer.transferId()).orElseThrow();
        var line=transfers.detail(fixture.organizationId(),transfer.transferId()).orElseThrow().lines().stream()
                .filter(item->item.id().equals(transfer.lineId())).findFirst().orElseThrow();
        UUID documentId=UUID.randomUUID();
        transfers.recordPhysicalDocument(new TransferRepository.PhysicalDocument(documentId,fixture.organizationId(),
                transfer.transferId(),identity.sourceStoreId(),identity.destinationStoreId(),identity.destinationStoreId(),
                commandId,"DESTINATION","DAMAGE","damage-"+commandId,"Record test damage","damage-evidence-"+commandId,
                fixture.actorId(),fixture.membershipId()));
        transfers.recordPhysicalDocumentLine(new TransferRepository.PhysicalDocumentLine(fixture.organizationId(),
                transfer.transferId(),documentId,transfer.lineId(),"DAMAGE",1,"loss-"+commandId,quantity,"DAMAGED",
                line.sku(),line.lotCode(),null));
    }

    private static TransferRepository.CustodyAppend custody(Fixture fixture, TransferSeed transfer,
            UUID commandId, long expectedVersion, TransferMovementType type, long quantity, StockLink link) {
        return new TransferRepository.CustodyAppend(UUID.randomUUID(), fixture.organizationId(),
                transfer.transferId(), transfer.lineId(), expectedVersion, type, commandId, fixture.actorId(),
                fixture.membershipId(), quantity, link == null ? null : link.storeId(),
                link == null ? null : link.movementId(), link == null ? null : link.positionId(),
                "Physical custody evidence", "custody-document");
    }

    private static Fixture fixture() {
        return inTransaction(() -> {
            UUID actor = UUID.randomUUID();
            UUID organization = UUID.randomUUID();
            UUID otherOrganization = UUID.randomUUID();
            UUID membership = UUID.randomUUID();
            UUID otherMembership = UUID.randomUUID();
            UUID sourceStore = UUID.randomUUID();
            UUID destinationStore = UUID.randomUUID();
            UUID otherSourceStore = UUID.randomUUID();
            UUID otherDestinationStore = UUID.randomUUID();
            UUID sourceZone = UUID.randomUUID();
            UUID destinationZone = UUID.randomUUID();
            UUID otherSourceZone = UUID.randomUUID();
            UUID otherDestinationZone = UUID.randomUUID();
            UUID sourceLocation = UUID.randomUUID();
            UUID secondSourceLocation = UUID.randomUUID();
            UUID destinationLocation = UUID.randomUUID();
            UUID otherSourceLocation = UUID.randomUUID();
            UUID otherDestinationLocation = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            UUID lot = UUID.randomUUID();
            UUID otherProduct = UUID.randomUUID();
            UUID otherLot = UUID.randomUUID();
            UUID sourcePosition = UUID.randomUUID();
            UUID secondSourcePosition = UUID.randomUUID();
            UUID destinationPosition = UUID.randomUUID();
            UUID otherSourcePosition = UUID.randomUUID();
            UUID otherDestinationPosition = UUID.randomUUID();
            execute("INSERT INTO stockrescue.user_account(id,email_display) VALUES (?,?)",
                    actor, actor + "@example.test");
            execute("""
                    INSERT INTO stockrescue.organization(id,name,created_by_user_id)
                    VALUES (?,'Transfer test retailer',?),(?,'Other transfer retailer',?)
                    """, organization, actor, otherOrganization, actor);
            execute("""
                    INSERT INTO stockrescue.membership(id,org_id,user_id,org_role)
                    VALUES (?,?,?,'ORG_ADMIN'),(?,?,?,'ORG_ADMIN')
                    """, membership, organization, actor, otherMembership, otherOrganization, actor);
            execute("""
                    INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES
                    (?,?,'NORTH','North','UTC'),(?,?,'SOUTH','South','UTC'),
                    (?,?,'OTHER-N','Other North','UTC'),(?,?,'OTHER-S','Other South','UTC')
                    """, sourceStore, organization, destinationStore, organization,
                    otherSourceStore, otherOrganization, otherDestinationStore, otherOrganization);
            execute("""
                    INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES
                    (?,?,?,'Dry','AMBIENT'),(?,?,?,'Dry','AMBIENT'),
                    (?,?,?,'Dry','AMBIENT'),(?,?,?,'Dry','AMBIENT')
                    """, sourceZone, organization, sourceStore, destinationZone, organization, destinationStore,
                    otherSourceZone, otherOrganization, otherSourceStore, otherDestinationZone, otherOrganization, otherDestinationStore);
            execute("""
                    INSERT INTO stockrescue.storage_location(id,org_id,store_id,zone_id,code,name) VALUES
                    (?,?,?,?,'A-1','Aisle one'),(?,?,?,?,'A-2','Aisle two'),
                    (?,?,?,?,'B-1','Receiving'),(?,?,?,?,'O-1','Other source'),(?,?,?,?,'O-2','Other receiving')
                    """, sourceLocation, organization, sourceStore, sourceZone,
                    secondSourceLocation, organization, sourceStore, sourceZone,
                    destinationLocation, organization, destinationStore, destinationZone,
                    otherSourceLocation, otherOrganization, otherSourceStore, otherSourceZone,
                    otherDestinationLocation, otherOrganization, otherDestinationStore, otherDestinationZone);
            execute("""
                    INSERT INTO stockrescue.product(id,org_id,sku,name,base_unit,temperature_class) VALUES
                    (?,?,'BREAD','Bread','EA','AMBIENT'),(?,?,'OTHER','Other product','EA','AMBIENT')
                    """, product, organization, otherProduct, otherOrganization);
            execute("""
                    INSERT INTO stockrescue.manufacturer_lot(id,org_id,product_id,lot_code) VALUES
                    (?,?,?,'LOT-A'),(?,?,?,'OTHER-LOT')
                    """, lot, organization, product, otherLot, otherOrganization, otherProduct);
            inventory.createPosition(sourcePosition, organization, sourceStore, sourceLocation, product, lot);
            inventory.createPosition(secondSourcePosition, organization, sourceStore, secondSourceLocation, product, lot);
            inventory.createPosition(destinationPosition, organization, destinationStore, destinationLocation, product, lot);
            inventory.createPosition(otherSourcePosition, otherOrganization, otherSourceStore, otherSourceLocation, otherProduct, otherLot);
            inventory.createPosition(otherDestinationPosition, otherOrganization, otherDestinationStore,
                    otherDestinationLocation, otherProduct, otherLot);
            return new Fixture(actor, organization, otherOrganization, membership, otherMembership,
                    sourceStore, destinationStore, otherSourceStore, otherDestinationStore,
                    sourceLocation, secondSourceLocation, destinationLocation, otherSourceLocation, otherDestinationLocation,
                    product, lot, otherProduct, otherLot, sourcePosition, secondSourcePosition,
                    destinationPosition, otherSourcePosition, otherDestinationPosition);
        });
    }

    private static LineSeed line(UUID sourcePosition, UUID destinationLocation, long quantity, long expectedSourceVersion) {
        return new LineSeed(UUID.randomUUID(), sourcePosition, destinationLocation, quantity, expectedSourceVersion);
    }

    private static <T> T inTransaction(java.util.concurrent.Callable<T> work) {
        return transactions.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static long count(String table, UUID organizationId, UUID id) {
        return jdbc.sql("SELECT count(*) FROM stockrescue." + table + " WHERE org_id=:org AND id=:id")
                .param("org", organizationId).param("id", id).query(Long.class).single();
    }

    private static long countByTransfer(String table, UUID organizationId, UUID transferId) {
        return jdbc.sql("SELECT count(*) FROM stockrescue." + table + " WHERE org_id=:org AND transfer_id=:transfer")
                .param("org", organizationId).param("transfer", transferId).query(Long.class).single();
    }

    private static void rejectsSqlState(String expected, SqlWork work) {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            try {
                work.run();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }))
                .satisfies(error -> {
                    Throwable root = error;
                    while (root.getCause() != null) root = root.getCause();
                    assertThat(root).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) root).getSQLState()).isEqualTo(expected);
                });
    }

    private static void execute(String sql, Object... values) {
        Connection connection = DataSourceUtils.getConnection(source);
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.setQueryTimeout(10);
            statement.execute();
        } catch (SQLException error) {
            throw new UncategorizedSQLException("transfer test fixture", sql, error);
        } finally {
            DataSourceUtils.releaseConnection(connection, source);
        }
    }

    private static DriverManagerDataSource dataSource(PostgreSQLContainer database) {
        var bindings = database.getDockerClient().inspectContainerCmd(database.getContainerId()).exec()
                .getNetworkSettings().getPorts().getBindings();
        var port = bindings.get(com.github.dockerjava.api.model.ExposedPort.tcp(5432))[0].getHostPortSpec();
        return new DriverManagerDataSource("jdbc:postgresql://" + database.getHost() + ":" + port + "/"
                + database.getDatabaseName(), "stockrescue_app", "test-app-password");
    }

    private record Fixture(UUID actorId, UUID organizationId, UUID otherOrganizationId,
            UUID membershipId, UUID otherMembershipId, UUID sourceStoreId, UUID destinationStoreId,
            UUID otherSourceStoreId, UUID otherDestinationStoreId, UUID sourceLocationId,
            UUID secondSourceLocationId, UUID destinationLocationId, UUID otherSourceLocationId,
            UUID otherDestinationLocationId, UUID productId, UUID lotId, UUID otherProductId,
            UUID otherLotId, UUID sourcePositionId, UUID secondSourcePositionId,
            UUID destinationPositionId, UUID otherSourcePositionId, UUID otherDestinationPositionId) { }

    private record LineSeed(UUID id, UUID sourcePositionId, UUID destinationLocationId,
            long quantity, long expectedSourceVersion) { }

    private record TransferSeed(UUID transferId, UUID createCommandId, UUID lineId, List<LineSeed> lines) {
        TransferSeed { lines = List.copyOf(lines); }
    }

    private record StockPosting(UUID commandId, UUID movementId, UUID requestId, String operation) { }

    private record StockLink(UUID storeId, UUID movementId, UUID positionId) { }

    @FunctionalInterface
    private interface SqlWork { void run() throws Exception; }
}
