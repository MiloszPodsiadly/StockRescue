package com.stockrescue.transfers.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmation.ProductProfile;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmation.SourceType;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmationService;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmationSubmission;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.inventory.application.InventoryCommands.PositionReference;
import com.stockrescue.inventory.application.InventoryCommands.ReceiveGoods;
import com.stockrescue.inventory.application.InventoryCommands.StockChange;
import com.stockrescue.inventory.application.InventoryService;
import com.stockrescue.inventory.application.InventoryService.TransferDispatchLine;
import com.stockrescue.inventory.messaging.InventoryOutboxRelay;
import com.stockrescue.support.InfrastructureTestBase;
import com.stockrescue.stores.application.LaneService;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import com.stockrescue.transfers.messaging.TransferEventCodec;
import com.stockrescue.transfers.messaging.TransferEventContractException;
import com.stockrescue.transfers.messaging.TransferInboxProcessor;
import com.stockrescue.transfers.persistence.TransferInboxRepository;
import com.stockrescue.transfers.persistence.TransferOutboxRepository;
import com.stockrescue.transfers.persistence.TransferOutboxRedriveRepository;
import com.stockrescue.transfers.persistence.TransferRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.MDC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "stockrescue.inventory.outbox-relay.enabled=false")
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransferWorkflowTest extends InfrastructureTestBase {
    private static final String PASSWORD = "transfer-workflow-test-passphrase";
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder passwords;
    @Autowired private InventoryService inventory;
    @Autowired private FoodSafetyProductClassificationConfirmationService classification;
    @Autowired private LaneService laneService;
    @Autowired private TransferOutboxRepository transferOutbox;
    @Autowired private TransferOutboxRedriveRepository transferRedrives;
    @Autowired private TransferRepository transfers;
    @Autowired private TransferEventCodec transferEventCodec;
    @Autowired private TransferInboxProcessor transferInbox;
    @Autowired private InventoryOutboxRelay relay;
    @Autowired private TransactionTemplate transactions;
    @MockitoSpyBean private KafkaTemplate kafka;
    private static UUID restartOrganization;
    private static UUID restartTransfer;
    private static UUID restartLine;
    private static UUID restartPosition;
    private Fixture fixture;
    private String passwordHash;

    @BeforeEach
    void createFixture() {
        passwordHash=passwords.encode(PASSWORD);
        fixture = new Fixture();
    }

    @Test
    @Order(1)
    @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
    void committedDispatchWaitsInTheOutboxWhenTheBackendStops() throws Exception {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
            relay.dispatchOnce();
            assertThat(transferOutbox.pendingCount()).isZero();
        });
        var browser=fixture.owner();
        var prepared=preparePickedTransfer(browser,2);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
            relay.dispatchOnce();
            assertThat(transferOutbox.pendingCount()).isZero();
            assertThat(jdbc.sql("SELECT aggregate_version || ':' || status FROM stockrescue.transfer_change_projection WHERE consumer_name='transfer-change-projection-v1' AND org_id=:org AND transfer_id=:transfer")
                    .param("org",fixture.organization).param("transfer",prepared.transferId()).query(String.class).single())
                    .isEqualTo("3:PICKED");
        });

        dispatch(browser,prepared,3,"dispatch-before-backend-restart");
        assertThat(stockOnHand(fixture.sourceStore,fixture.sourceLocation)).isEqualTo(3);
        assertThat(jdbc.sql("SELECT dispatched || ':' || physically_received || ':' || confirmed_loss || ':' || physically_returned || ':' || outstanding FROM stockrescue.transfer_custody_account WHERE org_id=:org AND transfer_id=:transfer AND line_id=:line")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).param("line",prepared.lineId())
                .query(String.class).single()).isEqualTo("2:0:0:0:2");
        assertThat(transferOutbox.pendingCount()).isEqualTo(1);
        UUID dispatchEvent=transferEventId(prepared.transferId(),4);
        org.mockito.Mockito.doAnswer(invocation->{
            @SuppressWarnings("unchecked") var record=(ProducerRecord<String,String>)invocation.getArgument(0);
            if(record.value().contains(dispatchEvent.toString()))
                return java.util.concurrent.CompletableFuture.failedFuture(new KafkaException("simulated broker outage"));
            return invocation.callRealMethod();
        }).when(kafka).send(org.mockito.ArgumentMatchers.any(ProducerRecord.class));
        relay.dispatchOnce();
        assertThat(jdbc.sql("SELECT last_error_code FROM stockrescue.transfer_outbox_delivery WHERE event_id=:event")
                .param("event",dispatchEvent).query(String.class).single()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(transferOutbox.pendingCount()).isEqualTo(1);
        restartOrganization=fixture.organization;
        restartTransfer=prepared.transferId();
        restartLine=prepared.lineId();
        restartPosition=fixture.position;
    }

    @Test
    @Order(2)
    void pendingDispatchPublishesAfterBackendRestartWithoutRepeatingTheLedgerEffect() {
        assertThat(restartOrganization).isNotNull();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
            relay.dispatchOnce();
            assertThat(transferOutbox.pendingCount()).isZero();
            assertThat(jdbc.sql("SELECT aggregate_version || ':' || status FROM stockrescue.transfer_change_projection WHERE consumer_name='transfer-change-projection-v1' AND org_id=:org AND transfer_id=:transfer")
                    .param("org",restartOrganization).param("transfer",restartTransfer).query(String.class).single())
                    .isEqualTo("4:IN_TRANSIT");
        });
        assertThat(jdbc.sql("SELECT on_hand FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org",restartOrganization).param("position",restartPosition).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT dispatched || ':' || physically_received || ':' || confirmed_loss || ':' || physically_returned || ':' || outstanding FROM stockrescue.transfer_custody_account WHERE org_id=:org AND transfer_id=:transfer AND line_id=:line")
                .param("org",restartOrganization).param("transfer",restartTransfer).param("line",restartLine)
                .query(String.class).single()).isEqualTo("2:0:0:0:2");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_inbox WHERE consumer_name='transfer-change-projection-v1' AND org_id=:org AND transfer_id=:transfer AND aggregate_version=4 AND applied_at IS NOT NULL")
                .param("org",restartOrganization).param("transfer",restartTransfer).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_custody_movement WHERE org_id=:org AND transfer_id=:transfer AND movement_type='DISPATCH'")
                .param("org",restartOrganization).param("transfer",restartTransfer).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void createReplayAndRequestCancellationKeepInventoryUntouched() throws Exception {
        var browser = fixture.owner();
        var requestId = UUID.randomUUID();
        var request = createRequest(requestId, fixture.sourceStore, fixture.destinationStore,
                fixture.position, fixture.destinationLocation, 2, 0, "Review inter-store request");
        var created = browser.write("POST", base(), request);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        var outcome = tree(created);
        UUID transferId = UUID.fromString(outcome.path("transferId").asString());
        assertThat(outcome.path("status").asString()).isEqualTo("REQUESTED");
        assertThat(outcome.path("version").asLong()).isZero();
        assertThat(outcome.path("stockMovementId").isNull()).isTrue();

        var replay = browser.write("POST", base(), request);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(tree(replay)).isEqualTo(outcome);
        problem(browser.write("POST", base(), createRequest(requestId, fixture.sourceStore, fixture.destinationStore,
                fixture.position, fixture.destinationLocation, 3, 0, "Changed request")), 409, "IDEMPOTENCY_CONFLICT");

        var detail = browser.get(base() + "/" + transferId);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(tree(detail).path("lines")).hasSize(1);
        assertThat(tree(detail).path("lines").get(0).path("requestedQuantity").asLong()).isEqualTo(2);
        var recovery = browser.get(base() + "/commands/CREATE/" + requestId);
        assertThat(recovery.statusCode()).isEqualTo(200);
        assertThat(tree(recovery).path("outcome")).isEqualTo(outcome);
        assertThat(recovery.headers().firstValue("Cache-Control").orElse("")).contains("no-store");

        var cancellationId = UUID.randomUUID();
        var cancelled = browser.write("POST", base() + "/" + transferId + "/cancel",
                decision(cancellationId, 0, "No longer required"));
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(tree(cancelled).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(tree(cancelled).path("version").asLong()).isEqualTo(1);
        assertThat(tree(browser.write("POST", base() + "/" + transferId + "/cancel",
                decision(cancellationId, 0, "No longer required")))).isEqualTo(tree(cancelled));
        problem(browser.write("POST", base() + "/" + transferId + "/cancel",
                decision(cancellationId, 0, "Changed cancellation reason")), 409, "IDEMPOTENCY_CONFLICT");
        problem(browser.write("POST", base() + "/" + transferId + "/cancel",
                decision(UUID.randomUUID(), 1, "Try another cancel")), 409, "INVENTORY_CONFLICT");

        assertThat(count("transfer_order", fixture.organization)).isEqualTo(1);
        assertThat(count("transfer_line", fixture.organization)).isEqualTo(1);
        assertThat(count("transfer_transition", fixture.organization)).isEqualTo(2);
        assertThat(count("transfer_command_receipt", fixture.organization)).isEqualTo(2);
        assertThat(count("inventory_command_receipt", fixture.organization)).isZero();
        assertThat(count("stock_movement", fixture.organization)).isZero();
        assertThat(jdbc.sql("SELECT on_hand+reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND resource_type='transfer'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void transferQueueShowsCurrentStoreScopeAndFiltersBySavedLifecycleStatus() throws Exception {
        var browser = fixture.owner();
        var created = browser.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 2, 0,
                "Review an inter-store request"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(created).path("transferId").asString());

        var queue = browser.get(base() + "?storeId=" + fixture.sourceStore + "&offset=0&limit=50");
        assertThat(queue.statusCode()).as(queue.body()).isEqualTo(200);
        var queueBody = tree(queue);
        assertThat(queueBody.path("items")).hasSize(1);
        var item = queueBody.path("items").get(0);
        assertThat(item.path("id").asString()).isEqualTo(transferId.toString());
        assertThat(item.path("sourceStoreId").asString()).isEqualTo(fixture.sourceStore.toString());
        assertThat(item.path("destinationStoreId").asString()).isEqualTo(fixture.destinationStore.toString());
        assertThat(item.path("status").asString()).isEqualTo("REQUESTED");
        assertThat(item.path("outstandingQuantity").asLong()).isZero();
        var filtered = browser.get(base() + "?storeId=" + fixture.sourceStore + "&status=APPROVED&offset=0&limit=50");
        assertThat(filtered.statusCode()).as(filtered.body()).isEqualTo(200);
        assertThat(tree(filtered).path("items")).isEmpty();
    }

    @Test
    void rejectionIsRecordedOnceAndForeignStorageContextIsNotRevealed() throws Exception {
        var browser = fixture.owner();
        var transfer = browser.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 1, 0, "Request stock movement"));
        assertThat(transfer.statusCode()).as(transfer.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(transfer).path("transferId").asString());
        var requestId = UUID.randomUUID();
        var rejected = browser.write("POST", base() + "/" + transferId + "/reject", decision(requestId, 0, "Not needed this week"));
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        assertThat(tree(rejected).path("status").asString()).isEqualTo("REJECTED");
        assertThat(tree(browser.write("POST", base() + "/" + transferId + "/reject",
                decision(requestId, 0, "Not needed this week")))).isEqualTo(tree(rejected));
        problem(browser.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                UUID.randomUUID(), fixture.position, fixture.destinationLocation, 1, 0, "Foreign destination")), 404, "RESOURCE_NOT_FOUND");
        problem(browser.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.sourceLocation, 1, 0, "Foreign destination location")),
                404, "RESOURCE_NOT_FOUND");
        problem(browser.get(base() + "/" + UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        assertThat(count("transfer_order", fixture.organization)).isEqualTo(1);
        assertThat(count("transfer_transition", fixture.organization)).isEqualTo(2);
        assertThat(count("inventory_command_receipt", fixture.organization)).isZero();
    }

    @Test
    void staleSourceVersionDoesNotCreateTransferOrChangeInventory() throws Exception {
        var browser = fixture.owner();
        problem(browser.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 1, 1, "Stale source review")),
                409, "INVENTORY_CONFLICT");

        assertThat(count("transfer_order", fixture.organization)).isZero();
        assertThat(count("transfer_line", fixture.organization)).isZero();
        assertThat(count("transfer_command_receipt", fixture.organization)).isZero();
        assertThat(count("inventory_command_receipt", fixture.organization)).isZero();
        assertThat(count("stock_movement", fixture.organization)).isZero();
        assertThat(jdbc.sql("SELECT on_hand+reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(Long.class).single()).isZero();
    }

    @Test
    void requesterCanApproveAndCancelTheirOwnTransferWithOneOwnedReservation() throws Exception {
        var browser = fixture.owner();
        long sourceVersion = fixture.seedSource(5);
        var transfer = browser.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 2, sourceVersion, "Reserve reviewed stock"));
        assertThat(transfer.statusCode()).as(transfer.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(transfer).path("transferId").asString());

        UUID approveRequestId = UUID.randomUUID();
        var approveRequest = decision(approveRequestId, 0, "Approve reviewed stock movement");
        var approved = browser.write("POST", base() + "/" + transferId + "/approve", approveRequest);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        var approvedOutcome = tree(approved);
        assertThat(approvedOutcome.path("status").asString()).isEqualTo("APPROVED");
        assertThat(approvedOutcome.path("version").asLong()).isEqualTo(1);
        assertThat(approvedOutcome.path("stockMovementId").asString()).isNotBlank();
        assertThat(tree(browser.write("POST", base() + "/" + transferId + "/approve", approveRequest)))
                .isEqualTo(approvedOutcome);
        problem(browser.write("POST", base() + "/" + transferId + "/approve",
                decision(approveRequestId, 0, "Changed approval reason")), 409, "IDEMPOTENCY_CONFLICT");
        problem(browser.write("POST", base() + "/" + transferId + "/approve",
                decision(UUID.randomUUID(), 1, "Approve a second time")), 409, "INVENTORY_CONFLICT");

        var approvedDetail = tree(browser.get(base() + "/" + transferId));
        UUID allocationId = UUID.fromString(approvedDetail.path("lines").get(0).path("reservationAllocationId").asString());
        assertThat(approvedDetail.path("lines").get(0).path("expectedSourceVersion").asLong()).isEqualTo(sourceVersion);
        assertThat(jdbc.sql("SELECT on_hand FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(Long.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT state FROM stockrescue.stock_allocation WHERE org_id=:org AND store_id=:store AND id=:allocation")
                .param("org", fixture.organization).param("store", fixture.sourceStore).param("allocation", allocationId)
                .query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_transition WHERE org_id=:org AND transfer_id=:transfer AND status_after='APPROVED'")
                .param("org", fixture.organization).param("transfer", transferId).query(Long.class).single()).isEqualTo(1);

        var cancelRequest = decision(UUID.randomUUID(), 1, "Release unused transfer reservation");
        var cancelled = browser.write("POST", base() + "/" + transferId + "/cancel", cancelRequest);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(tree(cancelled).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(tree(browser.write("POST", base() + "/" + transferId + "/cancel", cancelRequest)))
                .isEqualTo(tree(cancelled));
        assertThat(jdbc.sql("SELECT reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT state FROM stockrescue.stock_allocation WHERE org_id=:org AND store_id=:store AND id=:allocation")
                .param("org", fixture.organization).param("store", fixture.sourceStore).param("allocation", allocationId)
                .query(String.class).single()).isEqualTo("RELEASED");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_RESERVE'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_RELEASE'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND resource_type='transfer' AND resource_id=:transfer")
                .param("org", fixture.organization).param("transfer", transferId).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void staleSourceVersionBlocksApprovalWithoutReservation() throws Exception {
        var browser = fixture.owner();
        long sourceVersion = fixture.seedSource(5);
        var transfer = browser.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 2, sourceVersion, "Review stock before approval"));
        assertThat(transfer.statusCode()).as(transfer.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(transfer).path("transferId").asString());

        fixture.withFixtureCorrelation(() -> inventory.receiveGoods(fixture.ownerId, fixture.organization,
                fixture.sourceStore, fixture.receipt(1)));
        problem(browser.write("POST", base() + "/" + transferId + "/approve",
                decision(UUID.randomUUID(), 0, "Reject stale source position")), 409, "INVENTORY_CONFLICT");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", transferId).query(String.class).single()).isEqualTo("REQUESTED");
        assertThat(jdbc.sql("SELECT reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND transfer_id=:transfer AND operation='APPROVE'")
                .param("org", fixture.organization).param("transfer", transferId).query(Long.class).single()).isZero();
    }

    @Test
    void recalledLotBlocksApprovalWithoutReservation() throws Exception {
        var browser = fixture.owner();
        long sourceVersion = fixture.seedSource(5);
        var transfer = browser.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 2, sourceVersion, "Review stock before approval"));
        assertThat(transfer.statusCode()).as(transfer.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(transfer).path("transferId").asString());
        var recalled = browser.write("POST", "/api/v1/organizations/" + fixture.organization + "/lots/" + fixture.lot
                + "/safety-status-evidence", Map.of("requestId", UUID.randomUUID(), "expectedVersion", 0,
                        "reason", "Reviewed current manufacturer recall notice", "recallState", "RECALLED",
                        "damageState", "NOT_REPORTED", "provenance", Map.of("source", "Observed document",
                                "reference", "supplier:recall-notice")));
        assertThat(recalled.statusCode()).as(recalled.body()).isEqualTo(201);
        problem(browser.write("POST", base() + "/" + transferId + "/approve",
                decision(UUID.randomUUID(), 0, "Re-evaluate recalled lot")), 422, "LOT_NOT_ELIGIBLE");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", transferId).query(String.class).single()).isEqualTo("REQUESTED");
        assertThat(jdbc.sql("SELECT reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND transfer_id=:transfer AND operation='APPROVE'")
                .param("org", fixture.organization).param("transfer", transferId).query(Long.class).single()).isZero();
    }

    @Test
    void competingApprovalsForTheLastUnitHaveOneWinner() throws Exception {
        var first = fixture.owner();
        var second = fixture.owner();
        long sourceVersion = fixture.seedSource(1);
        var firstTransfer = first.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 1, sourceVersion, "Request last available unit"));
        var secondTransfer = second.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 1, sourceVersion, "Request last available unit"));
        assertThat(firstTransfer.statusCode()).as(firstTransfer.body()).isEqualTo(200);
        assertThat(secondTransfer.statusCode()).as(secondTransfer.body()).isEqualTo(200);
        UUID firstId = UUID.fromString(tree(firstTransfer).path("transferId").asString());
        UUID secondId = UUID.fromString(tree(secondTransfer).path("transferId").asString());

        var approvals = concurrent(
                () -> first.write("POST", base() + "/" + firstId + "/approve", decision(UUID.randomUUID(), 0, "Approve first request")),
                () -> second.write("POST", base() + "/" + secondId + "/approve", decision(UUID.randomUUID(), 0, "Approve second request")));
        assertThat(approvals.stream().filter(response -> response.statusCode() == 200)).hasSize(1);
        assertThat(approvals.stream().filter(response -> response.statusCode() == 409)).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_order WHERE org_id=:org AND status='APPROVED'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_RESERVE'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void opposingTransfersDispatchWithoutStoreLockDeadlock() throws Exception {
        var forwardOperator = fixture.owner();
        var reverseOperator = fixture.owner();
        fixture.addReverseLane();

        long sourceVersion = fixture.seedSource(5);
        var forward = preparePickedTransfer(forwardOperator, fixture.sourceStore, fixture.destinationStore,
                fixture.position, fixture.destinationLocation, 2, sourceVersion);
        long destinationVersion = fixture.seedDestination(5);
        UUID destinationPosition = fixture.stockPosition(fixture.destinationStore, fixture.destinationLocation);
        var reverse = preparePickedTransfer(reverseOperator, fixture.destinationStore, fixture.sourceStore,
                destinationPosition, fixture.sourceLocation, 2, destinationVersion);

        var outcomes = concurrent(
                () -> forwardOperator.write("POST", base() + "/" + forward.transferId() + "/dispatch",
                        dispatchRequest(UUID.randomUUID(), 3, "dispatch-forward", forward, 2)),
                () -> reverseOperator.write("POST", base() + "/" + reverse.transferId() + "/dispatch",
                        dispatchRequest(UUID.randomUUID(), 3, "dispatch-reverse", reverse, 2)));
        assertThat(outcomes).allSatisfy(response -> {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(tree(response).path("status").asString()).isEqualTo("IN_TRANSIT");
        });
        assertThat(custody(forward.transferId(), forward.lineId())).isEqualTo("2:0:0:0:2");
        assertThat(custody(reverse.transferId(), reverse.lineId())).isEqualTo("2:0:0:0:2");
        assertThat(stockOnHand(fixture.sourceStore, fixture.sourceLocation)).isEqualTo(3);
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(3);
    }

    @Test
    void completePickAndCompetingDispatchesMoveReservedStockToTransitOnce() throws Exception {
        var first = fixture.owner();
        var second = fixture.owner();
        var prepared = preparePickedTransfer(first, 2);
        var firstRequestId = UUID.randomUUID();
        var secondRequestId = UUID.randomUUID();
        var firstDispatch = dispatchRequest(firstRequestId, 3, "dispatch-slip-1", prepared, 2);
        var secondDispatch = dispatchRequest(secondRequestId, 3, "dispatch-slip-1", prepared, 2);

        var outcomes = concurrent(
                () -> first.write("POST", base() + "/" + prepared.transferId() + "/dispatch", firstDispatch),
                () -> second.write("POST", base() + "/" + prepared.transferId() + "/dispatch", secondDispatch));
        assertThat(outcomes.stream().filter(response -> response.statusCode() == 200)).hasSize(1);
        assertThat(outcomes.stream().filter(response -> response.statusCode() == 409)).hasSize(1);
        int winnerIndex = outcomes.get(0).statusCode() == 200 ? 0 : 1;
        var successful = outcomes.get(winnerIndex);
        var winnerRequestId = winnerIndex == 0 ? firstRequestId : secondRequestId;
        assertThat(tree(successful).path("status").asString()).isEqualTo("IN_TRANSIT");
        assertThat(tree(successful).path("version").asLong()).isEqualTo(4);
        assertThat(tree(successful).path("stockMovementId").asString()).isNotBlank();

        // Treat the dispatch response as lost and recover the committed command read-only.
        var recovered = tree(first.get(base() + "/commands/DISPATCH/" + winnerRequestId));
        assertThat(recovered.path("outcome")).isEqualTo(tree(successful));
        var retry = first.write("POST", base() + "/" + prepared.transferId() + "/dispatch",
                winnerIndex == 0 ? firstDispatch : secondDispatch);
        assertThat(tree(retry)).isEqualTo(tree(successful));

        assertThat(jdbc.sql("SELECT on_hand FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT state FROM stockrescue.stock_allocation WHERE org_id=:org AND store_id=:store AND id=:allocation")
                .param("org", fixture.organization).param("store", fixture.sourceStore).param("allocation", prepared.allocationId())
                .query(String.class).single()).isEqualTo("CONSUMED");
        assertThat(jdbc.sql("SELECT dispatched || ':' || outstanding FROM stockrescue.transfer_custody_account WHERE org_id=:org AND transfer_id=:transfer AND line_id=:line")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).param("line", prepared.lineId())
                .query(String.class).single()).isEqualTo("2:2");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_OUT'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_custody_movement WHERE org_id=:org AND transfer_id=:transfer AND movement_type='DISPATCH'")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document WHERE org_id=:org AND transfer_id=:transfer AND document_type IN ('PICK','DISPATCH')")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isEqualTo(2);
        problem(first.write("POST", base() + "/" + prepared.transferId() + "/cancel",
                decision(UUID.randomUUID(), 4, "Attempt cancellation after dispatch")), 409, "INVENTORY_CONFLICT");
    }

    @Test
    void committedTransferEventsDeferOutOfOrderDuplicatesAndCannotMutateStock() throws Exception {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
            relay.dispatchOnce();
            assertThat(transferOutbox.pendingCount()).isZero();
        });
        var browser=fixture.owner();
        long stockVersion=fixture.seedSource(4);
        var created=browser.write("POST",base(),createRequest(UUID.randomUUID(),fixture.sourceStore,fixture.destinationStore,
                fixture.position,fixture.destinationLocation,2,stockVersion,"Create event ordering example"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        UUID transferId=UUID.fromString(tree(created).path("transferId").asString());
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
            relay.dispatchOnce();
            assertThat(transferOutbox.pendingCount()).isZero();
        });
        var approved=browser.write("POST",base()+"/"+transferId+"/approve",decision(UUID.randomUUID(),0,"Approve event ordering example"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        UUID versionOneId=transferEventId(transferId,1);
        var originalClaim=transferOutbox.claimDue(UUID.randomUUID(),1,Duration.ofSeconds(30)).getFirst();
        assertThat(originalClaim.id()).isEqualTo(versionOneId);
        assertThat(transferOutbox.park(originalClaim,"BROKER_UNAVAILABLE")).isTrue();
        var picking=browser.write("POST",base()+"/"+transferId+"/start-pick",decision(UUID.randomUUID(),1,"Begin the source pick"));
        assertThat(picking.statusCode()).as(picking.body()).isEqualTo(200);
        UUID versionTwoId=transferEventId(transferId,2);
        assertThat(transferOutbox.claimDue(UUID.randomUUID(),5,Duration.ofSeconds(30)))
                .noneMatch(claim->claim.id().equals(versionTwoId));
        String positionBefore=jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org",fixture.organization).param("position",fixture.position).query(String.class).single();

        var eventIds=jdbc.sql("SELECT id FROM stockrescue.transfer_outbox_event WHERE org_id=:org AND transfer_id=:transfer ORDER BY aggregate_version")
                .param("org",fixture.organization).param("transfer",transferId).query(UUID.class).list();
        assertThat(eventIds).containsExactly(transferEventId(transferId,0),versionOneId,versionTwoId);
        var first=transferOutbox.find(eventIds.get(0));
        var second=transferOutbox.find(eventIds.get(1));
        var third=transferOutbox.find(eventIds.get(2));
        var firstPayload=transferEventCodec.encode(transferEventCodec.fromOutbox(first));
        var secondPayload=transferEventCodec.encode(transferEventCodec.fromOutbox(second));
        var thirdPayload=transferEventCodec.encode(transferEventCodec.fromOutbox(third));

        String foreignTenantPayload=thirdPayload.replace(fixture.organization.toString(),UUID.randomUUID().toString());
        assertThatThrownBy(()->transferInbox.process(foreignTenantPayload,"transfer-security-check-v1"))
                .isInstanceOf(TransferEventContractException.class);
        assertThat(transferInbox.process(thirdPayload,"transfer-order-check-v1"))
                .isEqualTo(TransferInboxRepository.Outcome.DEFERRED);
        assertThat(transferInbox.process(firstPayload,"transfer-order-check-v1"))
                .isEqualTo(TransferInboxRepository.Outcome.APPLIED);
        assertThat(transferInbox.process(firstPayload,"transfer-order-check-v1"))
                .isEqualTo(TransferInboxRepository.Outcome.DUPLICATE);
        assertThat(transferInbox.process(secondPayload,"transfer-order-check-v1"))
                .isEqualTo(TransferInboxRepository.Outcome.APPLIED);
        assertThat(transferInbox.process(firstPayload,"transfer-order-check-v1"))
                .isEqualTo(TransferInboxRepository.Outcome.DUPLICATE);
        assertThat(jdbc.sql("SELECT aggregate_version || ':' || status FROM stockrescue.transfer_change_projection WHERE consumer_name='transfer-order-check-v1' AND org_id=:org AND transfer_id=:transfer")
                .param("org",fixture.organization).param("transfer",transferId).query(String.class).single()).isEqualTo("2:PICKING");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_inbox WHERE consumer_name='transfer-order-check-v1' AND applied_at IS NOT NULL")
                .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org",fixture.organization).param("position",fixture.position).query(String.class).single()).isEqualTo(positionBefore);
        String parkedPath="/api/v1/organizations/"+fixture.organization+"/transfers/outbox/parked-events";
        var diagnostics=browser.get(parkedPath);
        assertThat(diagnostics.statusCode()).isEqualTo(200);
        assertThat(diagnostics.body()).doesNotContain("payload","sourceStoreName","evidenceReference");
        var diagnostic=java.util.stream.StreamSupport.stream(tree(diagnostics).path("items").spliterator(),false)
                .filter(item->item.path("eventId").asString().equals(versionOneId.toString())).findFirst().orElseThrow();
        assertThat(fixture.operator().get(parkedPath).statusCode()).isEqualTo(403);
        UUID redriveRequestId=UUID.randomUUID();
        var redriveBody=Map.of("requestId",redriveRequestId,
                "expectedOriginalClaimVersion",diagnostic.path("originalClaimVersion").asLong(),
                "expectedOriginalAttemptCount",diagnostic.path("originalAttemptCount").asInt(),
                "sourceFingerprint",diagnostic.path("sourceFingerprint").asString(),
                "reason","Recover the approved transfer event","evidenceReference","incident:transfer-ordering");
        var staleBody=new java.util.LinkedHashMap<>(redriveBody);
        staleBody.put("expectedOriginalClaimVersion",diagnostic.path("originalClaimVersion").asLong()+1);
        problem(browser.write("POST",parkedPath+"/"+versionOneId+"/redrives",staleBody),409,"INVENTORY_CONFLICT");
        var acceptedResponse=browser.write("POST",parkedPath+"/"+versionOneId+"/redrives",redriveBody);
        assertThat(acceptedResponse.statusCode()).as(acceptedResponse.body()).isEqualTo(200);
        var accepted=tree(acceptedResponse);
        assertThat(accepted.path("state").asString()).isEqualTo("REDRIVE_QUEUED");
        assertThat(tree(browser.write("POST",parkedPath+"/"+versionOneId+"/redrives",redriveBody))).isEqualTo(accepted);
        var conflictingBody=new java.util.LinkedHashMap<>(redriveBody);
        conflictingBody.put("requestId",UUID.randomUUID());
        problem(browser.write("POST",parkedPath+"/"+versionOneId+"/redrives",conflictingBody),409,"INVENTORY_CONFLICT");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action='TRANSFER_OUTBOX_REDRIVE_REQUESTED' AND resource_id=:event")
                .param("org",fixture.organization).param("event",versionOneId).query(Long.class).single()).isEqualTo(1);
        var laterVersion=transferOutbox.find(versionTwoId);
        relay.dispatchOnce();
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->
                assertThat(transferRedrives.findRequest(fixture.organization,fixture.ownerId,redriveRequestId).publishedAt())
                        .isNotNull());
        relay.dispatchOnce();
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
            assertThat(jdbc.sql("SELECT published_at IS NOT NULL FROM stockrescue.transfer_outbox_delivery WHERE event_id=:event")
                    .param("event",laterVersion.id()).query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_inbox WHERE consumer_name='transfer-change-projection-v1' AND org_id=:org AND transfer_id=:transfer AND applied_at IS NOT NULL")
                    .param("org",fixture.organization).param("transfer",transferId).query(Long.class).single()).isEqualTo(3);
            assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                    .param("org",fixture.organization).param("position",fixture.position).query(String.class).single()).isEqualTo(positionBefore);
        });
    }

    private UUID transferEventId(UUID transferId,long version) {
        return jdbc.sql("SELECT id FROM stockrescue.transfer_outbox_event WHERE org_id=:org AND transfer_id=:transfer AND aggregate_version=:version")
                .param("org",fixture.organization).param("transfer",transferId).param("version",version).query(UUID.class).single();
    }

    @Test
    void partialPickAndPartialDispatchAreRejectedWithoutPhysicalOrStockEffects() throws Exception {
        var browser = fixture.owner();
        long sourceVersion = fixture.seedSource(5);
        var created = browser.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 2, sourceVersion,
                "Request a whole line for transfer"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(created).path("transferId").asString());
        var approved = browser.write("POST", base() + "/" + transferId + "/approve",
                decision(UUID.randomUUID(), 0, "Reserve the complete source line"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        var detail = tree(browser.get(base() + "/" + transferId));
        var line = detail.path("lines").get(0);
        UUID lineId = UUID.fromString(line.path("id").asString());
        UUID allocationId = UUID.fromString(line.path("reservationAllocationId").asString());
        var picking = browser.write("POST", base() + "/" + transferId + "/start-pick",
                decision(UUID.randomUUID(), 1, "Begin a full-line source count"));
        assertThat(tree(picking).path("status").asString()).isEqualTo("PICKING");
        problem(browser.write("POST", base() + "/" + transferId + "/pick",
                pickRequest(UUID.randomUUID(), 2, lineId, "pick-slip-wrong-sku", 2,
                        "WRONG-SKU", line.path("lotCode").asString())), 409, "INVENTORY_CONFLICT");
        var partialPick = browser.write("POST", base() + "/" + transferId + "/pick",
                pickRequest(UUID.randomUUID(), 2, lineId, "pick-slip-partial", 1,
                        line.path("sku").asString(), line.path("lotCode").asString()));
        problem(partialPick, 409, "INVENTORY_CONFLICT");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document WHERE org_id=:org AND transfer_id=:transfer AND document_type='PICK'")
                .param("org", fixture.organization).param("transfer", transferId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(String.class).single()).isEqualTo("5:2");

        var picked = browser.write("POST", base() + "/" + transferId + "/pick",
                pickRequest(UUID.randomUUID(), 2, lineId, "pick-slip-full", 2,
                        line.path("sku").asString(), line.path("lotCode").asString()));
        assertThat(picked.statusCode()).as(picked.body()).isEqualTo(200);
        var partialDispatch = browser.write("POST", base() + "/" + transferId + "/dispatch",
                dispatchRequest(UUID.randomUUID(), 3, "dispatch-slip-partial",
                        new PreparedTransfer(transferId, lineId, allocationId, 2,
                                line.path("sku").asString(), line.path("lotCode").asString(), sourceVersion + 1), 1));
        problem(partialDispatch, 409, "INVENTORY_CONFLICT");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", transferId).query(String.class).single()).isEqualTo("PICKED");
        assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(String.class).single()).isEqualTo("5:2");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_OUT'")
                .param("org", fixture.organization).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_custody_movement WHERE org_id=:org AND transfer_id=:transfer")
                .param("org", fixture.organization).param("transfer", transferId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document WHERE org_id=:org AND transfer_id=:transfer AND document_type='DISPATCH'")
                .param("org", fixture.organization).param("transfer", transferId).query(Long.class).single()).isZero();
    }

    @Test
    void dispatchRejectsAStockPositionChangedAfterPhysicalPick() throws Exception {
        var browser = fixture.owner();
        var prepared = preparePickedTransfer(browser, 2);
        fixture.withFixtureCorrelation(() -> inventory.adjustStock(fixture.ownerId, fixture.organization,
                fixture.sourceStore, new StockChange(UUID.randomUUID(), fixture.position, null, 1,
                        "Record a later source stock correction", "cycle-count:after-pick")));

        problem(browser.write("POST", base() + "/" + prepared.transferId() + "/dispatch",
                dispatchRequest(UUID.randomUUID(), 3, "dispatch-after-source-change", prepared, prepared.quantity())),
                409, "INVENTORY_CONFLICT");
        assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(String.class).single()).isEqualTo("6:2");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single()).isEqualTo("PICKED");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_OUT'")
                .param("org", fixture.organization).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_custody_movement WHERE org_id=:org AND transfer_id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isZero();
    }

    @Test
    void anInterruptionAfterSourcePostingRollsBackBeforeCustodyCanDiverge() throws Exception {
        var browser = fixture.owner();
        var prepared = preparePickedTransfer(browser, 2);
        var requestId = UUID.randomUUID();
        long outboxEventsBefore=jdbc.sql("SELECT count(*) FROM stockrescue.transfer_outbox_event WHERE org_id=:org AND transfer_id=:transfer")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single();
        assertThatThrownBy(() -> fixture.withFixtureCorrelation(() -> {
            transactions.executeWithoutResult(status -> {
                inventory.dispatchForTransfer(fixture.ownerId, fixture.organization, fixture.sourceStore, requestId,
                        prepared.transferId(), "Inject failure between stock and custody", "dispatch-failure-point",
                        java.util.List.of(new TransferDispatchLine(prepared.lineId(), fixture.position,
                                prepared.allocationId(), prepared.sourcePositionVersion(), prepared.quantity())));
                throw new IllegalStateException("Injected transaction interruption");
            });
            return null;
        })).hasMessage("Injected transaction interruption");
        assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(String.class).single()).isEqualTo("5:2");
        assertThat(jdbc.sql("SELECT state FROM stockrescue.stock_allocation WHERE org_id=:org AND store_id=:store AND id=:allocation")
                .param("org", fixture.organization).param("store", fixture.sourceStore).param("allocation", prepared.allocationId())
                .query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_OUT'")
                .param("org", fixture.organization).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_custody_movement WHERE org_id=:org AND transfer_id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single()).isEqualTo("PICKED");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_outbox_event WHERE org_id=:org AND transfer_id=:transfer")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single())
                .isEqualTo(outboxEventsBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_outbox_delivery WHERE org_id=:org AND transfer_id=:transfer")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single())
                .isEqualTo(outboxEventsBefore);
    }

    @Test
    void oneStoreManagerCanReceiveThePhysicalRemainderConfirmLossAndCloseTheDiscrepancy() throws Exception {
        var manager = fixture.storeManager();
        fixture.seedDestination(10);
        var prepared = preparePickedTransfer(manager, 20, 50);
        assertThat(stockOnHand(fixture.sourceStore, fixture.sourceLocation)).isEqualTo(50);
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(10);
        dispatch(manager, prepared, 3, "dispatch-proof-20");
        assertThat(stockOnHand(fixture.sourceStore, fixture.sourceLocation)).isEqualTo(30);
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(10);
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("20:0:0:0:20");

        var received = manager.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 4, 1, 18, "receipt-proof-18"));
        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);
        assertThat(tree(received).path("status").asString()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(tree(received).path("version").asLong()).isEqualTo(5);
        assertThat(tree(received).path("stockMovementId").asString()).isNotBlank();
        assertThat(stockOnHand(fixture.sourceStore, fixture.sourceLocation)).isEqualTo(30);
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(28);
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("20:18:0:0:2");
        problem(manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy",
                decision(UUID.randomUUID(), 5, "Cannot close while two units remain in transit")),
                409, "INVENTORY_CONFLICT");

        var damaged = manager.write("POST", base() + "/" + prepared.transferId() + "/damage",
                damageRequest(prepared, 5, 2, "damage-proof-2"));
        assertThat(damaged.statusCode()).as(damaged.body()).isEqualTo(200);
        assertThat(tree(damaged).path("status").asString()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(tree(damaged).path("stockMovementId").isNull()).isTrue();
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(28);
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("20:18:2:0:0");

        var closeRequest = decision(UUID.randomUUID(), 6, "Close fully documented custody discrepancy");
        var closed = manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy", closeRequest);
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        assertThat(tree(closed).path("status").asString()).isEqualTo("CLOSED_WITH_DISCREPANCY");
        assertThat(tree(manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy", closeRequest)))
                .isEqualTo(tree(closed));
        problem(manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy",
                decision(UUID.randomUUID(), 7, "A closed transfer cannot be closed again")), 409, "INVENTORY_CONFLICT");
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("20:18:2:0:0");

        var timeline = tree(manager.get(base() + "/" + prepared.transferId() + "/custody"));
        assertThat(timeline.path("items")).hasSize(3);
        assertThat(timeline.path("items").get(0).path("type").asString()).isEqualTo("LOSS");
        assertThat(timeline.path("items").get(1).path("type").asString()).isEqualTo("RECEIPT");
        assertThat(timeline.path("items").get(2).path("type").asString()).isEqualTo("DISPATCH");
        assertThat(timeline.path("items").get(0).path("stockMovementId").isNull()).isTrue();
        assertThat(timeline.path("items").get(1).path("stockMovementId").isNull()).isFalse();
        assertThat(timeline.path("items").get(2).path("stockMovementId").isNull()).isFalse();
        assertThat(transfers.custodyLedgerMismatchCount()).isZero();
    }

    @Test
    void destinationInventoryOperatorCanReceiveWithoutASourceStoreGrant() throws Exception {
        var owner = fixture.owner();
        var prepared = preparePickedTransfer(owner, 2);
        dispatch(owner, prepared, 3, "dispatch-destination-operator");
        var sourceOnly = fixture.operator();
        problem(sourceOnly.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 4, 0, 2, "receipt-without-destination-grant")), 404, "RESOURCE_NOT_FOUND");
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("2:0:0:0:2");
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isZero();
        var receiver = fixture.destinationOperator();

        problem(receiver.get(base() + "/in-transit?storeId=" + fixture.sourceStore), 404, "RESOURCE_NOT_FOUND");
        var visibleTransit = receiver.get(base() + "/in-transit?storeId=" + fixture.destinationStore);
        assertThat(visibleTransit.statusCode()).isEqualTo(200);
        assertThat(tree(visibleTransit).path("items")).hasSize(1);

        var response = receiver.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 4, 0, 2, "receipt-destination-operator"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(tree(response).path("status").asString()).isEqualTo("RECEIVED");
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(2);
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("2:2:0:0:0");
    }

    @Test
    void physicallyReceivedStockFromAnUnsafeLotIsQuarantinedAtTheDestination() throws Exception {
        var browser = fixture.owner();
        var prepared = preparePickedTransfer(browser, 2);
        dispatch(browser, prepared, 3, "dispatch-quarantined-receipt");
        var safety = browser.write("POST", "/api/v1/organizations/" + fixture.organization + "/lots/" + fixture.lot
                + "/safety-status-evidence", Map.of("requestId", UUID.randomUUID(), "expectedVersion", 0,
                        "reason", "A reviewed damage notice arrived during transit", "recallState", "NOT_REPORTED",
                        "damageState", "DAMAGED", "provenance", Map.of("source", "Observed damage notice",
                                "reference", "carrier:damage-notice")));
        assertThat(safety.statusCode()).as(safety.body()).isEqualTo(201);

        var received = browser.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 4, 0, 2, "receipt-damaged-lot"));

        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);
        assertThat(tree(received).path("status").asString()).isEqualTo("RECEIVED");
        assertThat(jdbc.sql("SELECT on_hand || ':' || quarantined FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND location_id=:location AND lot_id=:lot")
                .param("org", fixture.organization).param("store", fixture.destinationStore)
                .param("location", fixture.destinationLocation).param("lot", fixture.lot)
                .query(String.class).single()).isEqualTo("2:2");
        assertThat(jdbc.sql("SELECT disposition FROM stockrescue.transfer_physical_document_line WHERE org_id=:org AND transfer_id=:transfer AND document_type='RECEIPT'")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single())
                .isEqualTo("QUARANTINED");
    }

    @Test
    void recallRecordedDuringTransitQuarantinesPhysicalReceiptAndNeverMakesItAvailable() throws Exception {
        var browser = fixture.owner();
        var prepared = preparePickedTransfer(browser, 20);
        dispatch(browser, prepared, 3, "dispatch-recalled-lot");
        var recalled = browser.write("POST", "/api/v1/organizations/" + fixture.organization + "/lots/" + fixture.lot
                + "/safety-status-evidence", Map.of("requestId", UUID.randomUUID(), "expectedVersion", 0,
                        "reason", "A verified manufacturer recall arrived during transit", "recallState", "RECALLED",
                        "damageState", "NOT_REPORTED", "provenance", Map.of("source", "Manufacturer recall notice",
                                "reference", "recall:manufacturer-2026-11")));
        assertThat(recalled.statusCode()).as(recalled.body()).isEqualTo(201);

        var received = browser.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 4, 0, 20, "receipt-recalled-lot"));

        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);
        assertThat(tree(received).path("status").asString()).isEqualTo("RECEIVED");
        assertThat(jdbc.sql("SELECT on_hand || ':' || quarantined || ':' || available FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND location_id=:location AND lot_id=:lot")
                .param("org", fixture.organization).param("store", fixture.destinationStore)
                .param("location", fixture.destinationLocation).param("lot", fixture.lot)
                .query(String.class).single()).isEqualTo("20:20:0");
        assertThat(jdbc.sql("SELECT disposition FROM stockrescue.transfer_physical_document_line WHERE org_id=:org AND transfer_id=:transfer AND document_type='RECEIPT'")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single())
                .isEqualTo("QUARANTINED");
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("20:20:0:0:0");
    }

    @Test
    void revokedSourceManagerCannotApproveAnExistingRequestOrReserveStock() throws Exception {
        var manager = fixture.storeManager();
        long sourceVersion = fixture.seedSource(5);
        var created = manager.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 2, sourceVersion,
                "Request a transfer before grant revocation"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(created).path("transferId").asString());

        fixture.revokeGrant(fixture.operatorMembership, fixture.sourceStore);

        problem(manager.write("POST", base() + "/" + transferId + "/approve",
                decision(UUID.randomUUID(), 0, "Attempt approval after source grant revocation")),
                404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", transferId).query(String.class).single()).isEqualTo("REQUESTED");
        assertThat(jdbc.sql("SELECT reserved FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND id=:position")
                .param("org", fixture.organization).param("store", fixture.sourceStore).param("position", fixture.position)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND transfer_id=:transfer AND operation='APPROVE'")
                .param("org", fixture.organization).param("transfer", transferId).query(Long.class).single()).isZero();
    }

    @Test
    void recalledLotAfterPickingBlocksDispatchWithoutDebitingReservedStock() throws Exception {
        var browser = fixture.owner();
        var prepared = preparePickedTransfer(browser, 2);
        var recalled = browser.write("POST", "/api/v1/organizations/" + fixture.organization + "/lots/" + fixture.lot
                + "/safety-status-evidence", Map.of("requestId", UUID.randomUUID(), "expectedVersion", 0,
                        "reason", "A verified manufacturer recall arrived before dispatch", "recallState", "RECALLED",
                        "damageState", "NOT_REPORTED", "provenance", Map.of("source", "Manufacturer recall notice",
                                "reference", "recall:pre-dispatch")));
        assertThat(recalled.statusCode()).as(recalled.body()).isEqualTo(201);

        problem(browser.write("POST", base() + "/" + prepared.transferId() + "/dispatch",
                dispatchRequest(UUID.randomUUID(), 3, "dispatch-after-recall", prepared, 2)), 422, "LOT_NOT_ELIGIBLE");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single())
                .isEqualTo("PICKED");
        assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND id=:position")
                .param("org", fixture.organization).param("store", fixture.sourceStore).param("position", fixture.position)
                .query(String.class).single()).isEqualTo("5:2");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_OUT'")
                .param("org", fixture.organization).query(Long.class).single()).isZero();
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("0:0:0:0:0");
    }

    @Test
    void expiredLotAfterPickingBlocksDispatchWithoutDebitingReservedStock() throws Exception {
        var browser = fixture.owner();
        var prepared = preparePickedTransfer(browser, 2);
        var correction = browser.write("POST", "/api/v1/organizations/" + fixture.organization + "/lots/"
                + fixture.lot + "/corrections", Map.of("requestId", UUID.randomUUID(), "expectedVersion", 0,
                        "reason", "A reviewed package label confirms the use-by date has passed",
                        "evidenceReviewed", true, "identityState", "CONFIRMED",
                        "manufacturerIdentityKey", "maker:bread-lot",
                        "manufacturerProvenance", Map.of("source", "Manufacturer register", "reference", "register-record"),
                        "manufacturerVerified", true,
                        "dateMarking", Map.of("labeledValue", "2026-11-18", "type", "USE_BY", "precision", "DAY",
                                "provenance", Map.of("source", "Original package", "reference", "label-photo"))));
        assertThat(correction.statusCode()).as(correction.body()).isEqualTo(201);

        problem(browser.write("POST", base() + "/" + prepared.transferId() + "/dispatch",
                dispatchRequest(UUID.randomUUID(), 3, "dispatch-after-expiry-correction", prepared, 2)),
                422, "LOT_NOT_ELIGIBLE");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single())
                .isEqualTo("PICKED");
        assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND id=:position")
                .param("org", fixture.organization).param("store", fixture.sourceStore).param("position", fixture.position)
                .query(String.class).single()).isEqualTo("5:2");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_OUT'")
                .param("org", fixture.organization).query(Long.class).single()).isZero();
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("0:0:0:0:0");
    }

    @Test
    void routeRevisionAfterApprovalRequiresAReviewBeforeDispatch() throws Exception {
        var browser = fixture.owner();
        var prepared = preparePickedTransfer(browser, 2);
        var currentLane = laneService.detail(fixture.ownerId, fixture.organization, fixture.lane).lane();
        fixture.withFixtureCorrelation(() -> laneService.revise(fixture.ownerId, fixture.organization, fixture.lane,
                currentLane.version(), currentLane.latestRevision(), "13.0000", "EUR", 50, null,
                "Review a changed transport route"));

        problem(browser.write("POST", base() + "/" + prepared.transferId() + "/dispatch",
                dispatchRequest(UUID.randomUUID(), 3, "dispatch-after-route-revision", prepared, 2)),
                409, "INVENTORY_CONFLICT");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single())
                .isEqualTo("PICKED");
        assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND id=:position")
                .param("org", fixture.organization).param("store", fixture.sourceStore).param("position", fixture.position)
                .query(String.class).single()).isEqualTo("5:2");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_OUT'")
                .param("org", fixture.organization).query(Long.class).single()).isZero();
    }

    @Test
    void sourceOperatorCannotReconcileAnOpenTransferDiscrepancy() throws Exception {
        var owner = fixture.owner();
        var prepared = preparePickedTransfer(owner, 2);
        dispatch(owner, prepared, 3, "dispatch-restricted-reconciliation");
        var opened = owner.write("POST", base() + "/" + prepared.transferId() + "/open-discrepancy",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 4, "lineId", prepared.lineId(),
                        "expectedState", "Two units remain in transit", "observedState", "Custody requires review",
                        "reason", "Open an evidenced transit discrepancy", "evidenceReference", "carrier:review-17"));
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);

        var operator = fixture.operator();
        problem(operator.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy",
                decision(UUID.randomUUID(), 5, "Unprivileged reconciliation attempt")), 403, "ACCESS_DENIED");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single())
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("2:0:0:0:2");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND transfer_id=:transfer AND operation='CLOSE'")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isZero();
    }

    @Test
    void foreignOrganizationCannotReadTransferDetailsOrCustodyEvidence() throws Exception {
        var owner = fixture.owner();
        var prepared = preparePickedTransfer(owner, 2);
        dispatch(owner, prepared, 3, "dispatch-tenant-boundary");
        var foreignAdmin = fixture.separateOrganizationAdmin();
        String foreignBase = "/api/v1/organizations/" + fixture.foreignOrganization + "/transfers/" + prepared.transferId();

        var detail = foreignAdmin.get(foreignBase);
        problem(detail, 404, "RESOURCE_NOT_FOUND");
        assertThat(detail.body()).doesNotContain(prepared.transferId().toString(), fixture.sourceStore.toString(),
                fixture.destinationStore.toString(), "dispatch-tenant-boundary");
        problem(foreignAdmin.get(foreignBase + "/custody"), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void archivedSourceDoesNotBlockPhysicalReceiptByAnAuthorizedDestinationOperator() throws Exception {
        var owner = fixture.owner();
        var prepared = preparePickedTransfer(owner, 2);
        dispatch(owner, prepared, 3, "dispatch-after-source-archive");
        var archived = owner.write("POST", "/api/v1/organizations/" + fixture.organization + "/stores/"
                + fixture.sourceStore + "/archive", Map.of("version", 0, "reason", "Close the source store after dispatch"));
        assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);

        var receiver = fixture.destinationOperator();
        var received = receiver.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 4, 0, 2, "receipt-after-source-archive"));

        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);
        assertThat(tree(received).path("status").asString()).isEqualTo("RECEIVED");
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(2);
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("2:2:0:0:0");
    }

    @Test
    void archivedDestinationBlocksReceiptAndPreservesTransitForDiscrepancyReview() throws Exception {
        var owner = fixture.owner();
        var prepared = preparePickedTransfer(owner, 2);
        dispatch(owner, prepared, 3, "dispatch-before-destination-archive");
        var archived = owner.write("POST", "/api/v1/organizations/" + fixture.organization + "/stores/"
                + fixture.destinationStore + "/archive", Map.of("version", 0, "reason", "Close the destination store during transit"));
        assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);

        problem(owner.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 4, 0, 2, "receipt-to-archived-destination")), 404, "RESOURCE_NOT_FOUND");
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("2:0:0:0:2");
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isZero();

        var discrepancy = owner.write("POST", base() + "/" + prepared.transferId() + "/open-discrepancy",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 4, "lineId", prepared.lineId(),
                        "expectedState", "Destination remains active for physical receiving", "observedState", "Destination is archived",
                        "reason", "Preserve custody while the destination is unavailable", "evidenceReference", "store-closure:destination"));
        assertThat(discrepancy.statusCode()).as(discrepancy.body()).isEqualTo(200);
        assertThat(tree(discrepancy).path("status").asString()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(tree(owner.get(base() + "/in-transit?storeId=" + fixture.sourceStore)).path("items").get(0)
                .path("outstanding").asLong()).isEqualTo(2);
    }

    @Test
    void receiptRejectsWrongLotOverReceiptAndReusedPhysicalDocumentWithoutPostingStock() throws Exception {
        var browser = fixture.owner();
        var prepared = preparePickedTransfer(browser, 20);
        dispatch(browser, prepared, 3, "dispatch-receipt-validation");

        problem(browser.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 4, 0, 1, "receipt-wrong-lot", "OTHER-LOT")), 409, "INVENTORY_CONFLICT");
        problem(browser.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 4, 0, 21, "receipt-over-dispatch")), 409, "INVENTORY_CONFLICT");
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("20:0:0:0:20");
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document WHERE org_id=:org AND transfer_id=:transfer AND document_type='RECEIPT'")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isZero();

        var firstReceipt = browser.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 4, 0, 2, "physical-receipt-identity"));
        assertThat(firstReceipt.statusCode()).as(firstReceipt.body()).isEqualTo(200);
        problem(browser.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 5, 1, 2, "physical-receipt-identity")), 409, "RECEIPT_ALREADY_RECORDED");
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("20:2:0:0:18");
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_IN'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);

        var discrepancy = browser.write("POST", base() + "/" + prepared.transferId() + "/open-discrepancy",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 5, "lineId", prepared.lineId(),
                        "expectedState", "Two units remain outstanding", "observedState", "Carrier cannot confirm location",
                        "reason", "Record the documented carrier discrepancy", "evidenceReference", "carrier:case-88"));
        assertThat(discrepancy.statusCode()).as(discrepancy.body()).isEqualTo(200);
        var discrepancies = tree(browser.get(base() + "/discrepancies?storeId=" + fixture.destinationStore));
        assertThat(discrepancies.path("items")).hasSize(1);
        assertThat(discrepancies.path("items").get(0).path("type").asString()).isEqualTo("DISCREPANCY");
    }

    @Test
    void concurrentReceiptsCannotBookTheSameOutstandingQuantityTwice() throws Exception {
        var first = fixture.owner();
        var second = fixture.owner();
        var prepared = preparePickedTransfer(first, 2);
        dispatch(first, prepared, 3, "dispatch-receipt-race");
        var firstRequest = receiveRequest(prepared, 4, 0, 2, "receipt-race-first");
        var secondRequest = receiveRequest(prepared, 4, 0, 2, "receipt-race-second");

        var responses = concurrent(
                () -> first.write("POST", base() + "/" + prepared.transferId() + "/receive", firstRequest),
                () -> second.write("POST", base() + "/" + prepared.transferId() + "/receive", secondRequest));

        assertThat(responses.stream().filter(response -> response.statusCode() == 200)).hasSize(1);
        assertThat(responses.stream().filter(response -> response.statusCode() == 409)).hasSize(1);
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(2);
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("2:2:0:0:0");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_IN'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void returnRequestLeavesCustodyOutstandingUntilTheSourceConfirmsPhysicalReturn() throws Exception {
        var manager = fixture.storeManager();
        var prepared = preparePickedTransfer(manager, 5);
        dispatch(manager, prepared, 3, "dispatch-return-proof");
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("5:0:0:0:5");
        assertThat(stockOnHand(fixture.sourceStore, fixture.sourceLocation)).isEqualTo(3);

        var returnReference = "return-request-" + UUID.randomUUID();
        var requested = manager.write("POST", base() + "/" + prepared.transferId() + "/request-return",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 4, "documentReference", returnReference,
                        "lines", java.util.List.of(Map.of("lineId", prepared.lineId())),
                        "reason", "Return remaining goods to source", "evidenceReference", "carrier:return-request"));
        assertThat(requested.statusCode()).as(requested.body()).isEqualTo(200);
        assertThat(tree(requested).path("status").asString()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("5:0:0:0:5");
        assertThat(stockOnHand(fixture.sourceStore, fixture.sourceLocation)).isEqualTo(3);

        var inTransit = tree(manager.get(base() + "/in-transit?storeId=" + fixture.sourceStore));
        assertThat(inTransit.path("items")).hasSize(1);
        assertThat(inTransit.path("items").get(0).path("outstanding").asLong()).isEqualTo(5);
        var discrepancies = tree(manager.get(base() + "/discrepancies?storeId=" + fixture.destinationStore));
        assertThat(discrepancies.path("items")).hasSize(1);
        assertThat(discrepancies.path("items").get(0).path("type").asString()).isEqualTo("RETURN_REQUEST");

        var sourceVersion = stockVersion(fixture.sourceStore, fixture.position);
        var returned = manager.write("POST", base() + "/" + prepared.transferId() + "/confirm-return",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 5, "documentReference", returnReference,
                        "lines", java.util.List.of(Map.of("lineId", prepared.lineId(), "expectedSourceVersion", sourceVersion,
                                "documentLineReference", "returned-line-1", "observedSku", prepared.sku(),
                                "observedLotCode", prepared.lotCode())),
                        "reason", "Counted the returned goods at source", "evidenceReference", "source:return-receipt"));
        assertThat(returned.statusCode()).as(returned.body()).isEqualTo(200);
        assertThat(tree(returned).path("status").asString()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(tree(returned).path("stockMovementId").asString()).isNotBlank();
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("5:0:0:5:0");
        assertThat(stockOnHand(fixture.sourceStore, fixture.sourceLocation)).isEqualTo(8);
        assertThat(jdbc.sql("SELECT operation FROM stockrescue.stock_movement WHERE org_id=:org AND id=(SELECT stock_movement_id FROM stockrescue.transfer_custody_movement WHERE org_id=:org AND transfer_id=:transfer AND line_id=:line AND movement_type='RETURN')")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).param("line", prepared.lineId())
                .query(String.class).single()).isEqualTo("TRANSFER_RETURN");

        var closed = manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy",
                decision(UUID.randomUUID(), 6, "Close the documented physical return"));
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        assertThat(tree(closed).path("status").asString()).isEqualTo("CLOSED_WITH_DISCREPANCY");
    }

    @Test
    void changedCustodyAllowsAnUpdatedReturnRequestForTheRemainingUnits() throws Exception {
        var manager = fixture.storeManager();
        var prepared = preparePickedTransfer(manager, 2);
        dispatch(manager, prepared, 3, "dispatch-return-adjustment");

        var firstRequest = manager.write("POST", base() + "/" + prepared.transferId() + "/request-return",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 4,
                        "documentReference", "return-request-before-loss-" + UUID.randomUUID(),
                        "lines", java.util.List.of(Map.of("lineId", prepared.lineId())),
                        "reason", "Request return of the outstanding line", "evidenceReference", "carrier:return-request"));
        assertThat(firstRequest.statusCode()).as(firstRequest.body()).isEqualTo(200);

        var loss = manager.write("POST", base() + "/" + prepared.transferId() + "/damage",
                damageRequest(prepared, 5, 1, "loss-during-return-" + UUID.randomUUID()));
        assertThat(loss.statusCode()).as(loss.body()).isEqualTo(200);
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("2:0:1:0:1");

        var returnReference = "return-request-after-loss-" + UUID.randomUUID();
        var updatedRequest = manager.write("POST", base() + "/" + prepared.transferId() + "/request-return",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 6, "documentReference", returnReference,
                        "lines", java.util.List.of(Map.of("lineId", prepared.lineId())),
                        "reason", "Request the remaining unit after documenting loss",
                        "evidenceReference", "carrier:return-request-updated"));
        assertThat(updatedRequest.statusCode()).as(updatedRequest.body()).isEqualTo(200);

        var returned = manager.write("POST", base() + "/" + prepared.transferId() + "/confirm-return",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 7, "documentReference", returnReference,
                        "lines", java.util.List.of(Map.of("lineId", prepared.lineId(),
                                "expectedSourceVersion", stockVersion(fixture.sourceStore, fixture.position),
                                "documentLineReference", "returned-after-loss", "observedSku", prepared.sku(),
                                "observedLotCode", prepared.lotCode())),
                        "reason", "Count the remaining unit physically returned to source",
                        "evidenceReference", "source:return-after-loss"));
        assertThat(returned.statusCode()).as(returned.body()).isEqualTo(200);
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("2:0:1:1:0");

        var closed = manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy",
                decision(UUID.randomUUID(), 8, "Close the fully settled custody record"));
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        assertThat(tree(closed).path("status").asString()).isEqualTo("CLOSED_WITH_DISCREPANCY");
    }

    @Test
    void custodyConservationIsCheckedIndependentlyForEveryTransferLine() throws Exception {
        var manager = fixture.storeManager();
        long firstSourceVersion = fixture.seedSource(fixture.sourceLocation, 6);
        long secondSourceVersion = fixture.seedSource(fixture.secondSourceLocation, 7);
        var created = manager.write("POST", base(), Map.of("requestId", UUID.randomUUID(),
                "sourceStoreId", fixture.sourceStore, "destinationStoreId", fixture.destinationStore,
                "lines", java.util.List.of(
                        Map.of("sourcePositionId", fixture.position, "destinationLocationId", fixture.destinationLocation,
                                "quantity", 3, "expectedSourceVersion", firstSourceVersion),
                        Map.of("sourcePositionId", fixture.secondPosition, "destinationLocationId", fixture.secondDestinationLocation,
                                "quantity", 4, "expectedSourceVersion", secondSourceVersion)),
                "reason", "Move two independently counted lots", "evidenceReference", "transfer:two-lines"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(created).path("transferId").asString());
        assertThat(manager.write("POST", base() + "/" + transferId + "/approve",
                decision(UUID.randomUUID(), 0, "Reserve both source lines")).statusCode()).isEqualTo(200);
        assertThat(manager.write("POST", base() + "/" + transferId + "/start-pick",
                decision(UUID.randomUUID(), 1, "Start picking both source lines")).statusCode()).isEqualTo(200);
        var detail = tree(manager.get(base() + "/" + transferId));
        var lines = detail.path("lines");
        assertThat(lines).hasSize(2);
        var firstLine = lines.get(0);
        var secondLine = lines.get(1);
        long firstDispatched = firstLine.path("requestedQuantity").asLong();
        long secondDispatched = secondLine.path("requestedQuantity").asLong();
        var firstPick = manager.write("POST", base() + "/" + transferId + "/pick",
                pickRequest(UUID.randomUUID(), 2, UUID.fromString(firstLine.path("id").asString()),
                        "pick-two-line-a", firstDispatched, firstLine.path("sku").asString(), firstLine.path("lotCode").asString()));
        assertThat(firstPick.statusCode()).as(firstPick.body()).isEqualTo(200);
        var secondPick = manager.write("POST", base() + "/" + transferId + "/pick",
                pickRequest(UUID.randomUUID(), 3, UUID.fromString(secondLine.path("id").asString()),
                        "pick-two-line-b", secondDispatched, secondLine.path("sku").asString(), secondLine.path("lotCode").asString()));
        assertThat(secondPick.statusCode()).as(secondPick.body()).isEqualTo(200);

        var dispatch = manager.write("POST", base() + "/" + transferId + "/dispatch", Map.of(
                "requestId", UUID.randomUUID(), "expectedVersion", 4, "documentReference", "dispatch-two-lines",
                "lines", java.util.List.of(
                        Map.of("lineId", firstLine.path("id").asString(), "quantity", firstDispatched,
                                "observedSku", firstLine.path("sku").asString(), "observedLotCode", firstLine.path("lotCode").asString()),
                        Map.of("lineId", secondLine.path("id").asString(), "quantity", secondDispatched,
                                "observedSku", secondLine.path("sku").asString(), "observedLotCode", secondLine.path("lotCode").asString())),
                "reason", "Hand off both counted lines", "evidenceReference", "dispatch:two-lines"));
        assertThat(dispatch.statusCode()).as(dispatch.body()).isEqualTo(200);
        assertThat(custody(transferId, UUID.fromString(firstLine.path("id").asString())))
                .isEqualTo(firstDispatched + ":0:0:0:" + firstDispatched);
        assertThat(custody(transferId, UUID.fromString(secondLine.path("id").asString())))
                .isEqualTo(secondDispatched + ":0:0:0:" + secondDispatched);

        var receive = manager.write("POST", base() + "/" + transferId + "/receive", Map.of(
                "requestId", UUID.randomUUID(), "expectedVersion", 5, "documentReference", "receipt-two-lines",
                "physicallyReceivedAt", Instant.now(),
                "lines", java.util.List.of(
                        Map.of("lineId", firstLine.path("id").asString(), "quantity", firstDispatched - 1, "expectedDestinationVersion", 0,
                                "documentLineReference", "receipt-line-a", "observedSku", firstLine.path("sku").asString(),
                                "observedLotCode", firstLine.path("lotCode").asString()),
                        Map.of("lineId", secondLine.path("id").asString(), "quantity", secondDispatched, "expectedDestinationVersion", 0,
                                "documentLineReference", "receipt-line-b", "observedSku", secondLine.path("sku").asString(),
                                "observedLotCode", secondLine.path("lotCode").asString())),
                "reason", "Count each destination line", "evidenceReference", "receipt:two-lines"));
        assertThat(receive.statusCode()).as(receive.body()).isEqualTo(200);
        UUID firstLineId = UUID.fromString(firstLine.path("id").asString());
        UUID secondLineId = UUID.fromString(secondLine.path("id").asString());
        assertThat(custody(transferId, firstLineId)).isEqualTo(firstDispatched + ":" + (firstDispatched - 1) + ":0:0:1");
        assertThat(custody(transferId, secondLineId)).isEqualTo(secondDispatched + ":" + secondDispatched + ":0:0:0");

        var damage = manager.write("POST", base() + "/" + transferId + "/damage", Map.of(
                "requestId", UUID.randomUUID(), "expectedVersion", 6, "documentReference", "damage-two-lines",
                "reasonCode", "DAMAGED_IN_TRANSIT", "lines", java.util.List.of(
                        Map.of("lineId", firstLineId, "quantity", 1, "documentLineReference", "damage-line-a")),
                "reason", "Document the damaged outstanding unit", "evidenceReference", "damage:two-lines"));
        assertThat(damage.statusCode()).as(damage.body()).isEqualTo(200);
        assertThat(custody(transferId, firstLineId)).isEqualTo(firstDispatched + ":" + (firstDispatched - 1) + ":1:0:0");
        assertThat(custody(transferId, secondLineId)).isEqualTo(secondDispatched + ":" + secondDispatched + ":0:0:0");
        UUID firstDestination = UUID.fromString(firstLine.path("destinationLocationId").asString());
        UUID secondDestination = UUID.fromString(secondLine.path("destinationLocationId").asString());
        assertThat(stockOnHand(fixture.destinationStore, firstDestination)).isEqualTo(firstDispatched - 1);
        assertThat(stockOnHand(fixture.destinationStore, secondDestination)).isEqualTo(secondDispatched);
    }

    @Test
    void sourceOperatorCanCancelTheirOwnRequestAndRetryRequiresCurrentGrant() throws Exception {
        var operator = fixture.operator();
        var transfer = operator.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 1, 0, "Operator requested transfer"));
        assertThat(transfer.statusCode()).as(transfer.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(transfer).path("transferId").asString());
        var reject = operator.write("POST", base() + "/" + transferId + "/reject", decision(UUID.randomUUID(), 0, "Operator cannot approve"));
        problem(reject, 403, "ACCESS_DENIED");

        var requestId = UUID.randomUUID();
        var request = decision(requestId, 0, "Owner withdrew the request");
        var cancelled = operator.write("POST", base() + "/" + transferId + "/cancel", request);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(tree(operator.write("POST", base() + "/" + transferId + "/cancel", request)))
                .isEqualTo(tree(cancelled));

        jdbc.sql("UPDATE stockrescue.store_grant SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE membership_id=:membership AND store_id=:store")
                .param("membership", fixture.operatorMembership).param("store", fixture.sourceStore).update();
        problem(operator.write("POST", base() + "/" + transferId + "/cancel", request), 404, "RESOURCE_NOT_FOUND");
        assertThat(count("transfer_order", fixture.organization)).isEqualTo(1);
        assertThat(count("transfer_transition", fixture.organization)).isEqualTo(2);
        assertThat(count("inventory_command_receipt", fixture.organization)).isZero();
    }

    @Test
    void requiredReviewedVersionsCannotBeOmittedFromTransferCommands() throws Exception {
        var browser = fixture.owner();
        var requestId = UUID.randomUUID();
        var line = Map.of("sourcePositionId", fixture.position, "destinationLocationId", fixture.destinationLocation,
                "quantity", 1);
        var request = Map.of("requestId", requestId, "sourceStoreId", fixture.sourceStore,
                "destinationStoreId", fixture.destinationStore, "lines", java.util.List.of(line),
                "reason", "Request lacks a reviewed source version", "evidenceReference", "transfer-review:missing-version");
        problem(browser.write("POST", base(), request), 400, "INVALID_REQUEST");
        assertThat(count("transfer_order", fixture.organization)).isZero();
        assertThat(count("transfer_command_receipt", fixture.organization)).isZero();
    }

    private PreparedTransfer preparePickedTransfer(Fixture.Browser browser, long quantity) throws Exception {
        return preparePickedTransfer(browser, quantity, quantity + 3);
    }

    private PreparedTransfer preparePickedTransfer(Fixture.Browser browser, long quantity, long sourceQuantity) throws Exception {
        long sourceVersion = fixture.seedSource(sourceQuantity);
        return preparePickedTransfer(browser, fixture.sourceStore, fixture.destinationStore, fixture.position,
                fixture.destinationLocation, quantity, sourceVersion);
    }

    private PreparedTransfer preparePickedTransfer(Fixture.Browser browser, UUID sourceStore, UUID destinationStore,
            UUID sourcePosition, UUID destinationLocation, long quantity, long sourceVersion) throws Exception {
        var created = browser.write("POST", base(), createRequest(UUID.randomUUID(), sourceStore,
                destinationStore, sourcePosition, destinationLocation, quantity, sourceVersion,
                "Request a documented whole-line movement"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(created).path("transferId").asString());
        var approved = browser.write("POST", base() + "/" + transferId + "/approve",
                decision(UUID.randomUUID(), 0, "Reserve the current source units"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        var line = tree(browser.get(base() + "/" + transferId)).path("lines").get(0);
        UUID lineId = UUID.fromString(line.path("id").asString());
        UUID allocationId = UUID.fromString(line.path("reservationAllocationId").asString());
        var picking = browser.write("POST", base() + "/" + transferId + "/start-pick",
                decision(UUID.randomUUID(), 1, "Begin whole-line physical picking"));
        assertThat(picking.statusCode()).as(picking.body()).isEqualTo(200);
        var picked = browser.write("POST", base() + "/" + transferId + "/pick",
                pickRequest(UUID.randomUUID(), 2, lineId, "pick-slip-" + UUID.randomUUID(), quantity,
                        line.path("sku").asString(), line.path("lotCode").asString()));
        assertThat(picked.statusCode()).as(picked.body()).isEqualTo(200);
        long pickedVersion = jdbc.sql("""
                SELECT l.source_position_version FROM stockrescue.transfer_physical_document d
                JOIN stockrescue.transfer_physical_document_line l
                  ON l.org_id=d.org_id AND l.transfer_id=d.transfer_id AND l.document_id=d.id
                WHERE d.org_id=:org AND d.transfer_id=:transfer AND l.line_id=:line AND d.document_type='PICK'
                """)
                .param("org", fixture.organization).param("transfer", transferId).param("line", lineId)
                .query(Long.class).single();
        return new PreparedTransfer(transferId, lineId, allocationId, quantity,
                line.path("sku").asString(), line.path("lotCode").asString(), pickedVersion);
    }

    private void dispatch(Fixture.Browser browser, PreparedTransfer transfer, long expectedVersion,
            String documentReference) {
        var response = browser.write("POST", base() + "/" + transfer.transferId() + "/dispatch",
                dispatchRequest(UUID.randomUUID(), expectedVersion, documentReference, transfer, transfer.quantity()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(tree(response).path("status").asString()).isEqualTo("IN_TRANSIT");
    }

    private Map<String, Object> receiveRequest(PreparedTransfer transfer, long expectedTransferVersion,
            long expectedDestinationVersion, long quantity, String documentReference) {
        return receiveRequest(transfer, expectedTransferVersion, expectedDestinationVersion, quantity,
                documentReference, transfer.lotCode());
    }

    private Map<String, Object> receiveRequest(PreparedTransfer transfer, long expectedTransferVersion,
            long expectedDestinationVersion, long quantity, String documentReference, String observedLotCode) {
        return Map.of("requestId", UUID.randomUUID(), "expectedVersion", expectedTransferVersion,
                "documentReference", documentReference, "physicallyReceivedAt", Instant.now(),
                "lines", java.util.List.of(Map.of("lineId", transfer.lineId(), "quantity", quantity,
                        "expectedDestinationVersion", expectedDestinationVersion,
                        "documentLineReference", "receipt-line-" + UUID.randomUUID(),
                        "observedSku", transfer.sku(), "observedLotCode", observedLotCode)),
                "reason", "Counted the exact physical transfer receipt", "evidenceReference", "receipt:" + documentReference);
    }

    private Map<String, Object> damageRequest(PreparedTransfer transfer, long expectedTransferVersion,
            long quantity, String documentReference) {
        return Map.of("requestId", UUID.randomUUID(), "expectedVersion", expectedTransferVersion,
                "documentReference", documentReference, "reasonCode", "DAMAGED_IN_TRANSIT",
                "lines", java.util.List.of(Map.of("lineId", transfer.lineId(), "quantity", quantity,
                        "documentLineReference", "damage-line-" + UUID.randomUUID())),
                "reason", "Documented physical damage during transit", "evidenceReference", "damage:" + documentReference);
    }

    private long stockOnHand(UUID store, UUID location) {
        return jdbc.sql("SELECT coalesce((SELECT on_hand FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND location_id=:location AND lot_id=:lot),0)")
                .param("org", fixture.organization).param("store", store).param("location", location)
                .param("lot", fixture.lot).query(Long.class).single();
    }

    private long stockVersion(UUID store, UUID position) {
        return jdbc.sql("SELECT version FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND id=:position")
                .param("org", fixture.organization).param("store", store).param("position", position).query(Long.class).single();
    }

    private String custody(UUID transferId, UUID lineId) {
        return jdbc.sql("SELECT dispatched || ':' || physically_received || ':' || confirmed_loss || ':' || physically_returned || ':' || outstanding FROM stockrescue.transfer_custody_account WHERE org_id=:org AND transfer_id=:transfer AND line_id=:line")
                .param("org", fixture.organization).param("transfer", transferId).param("line", lineId)
                .query(String.class).single();
    }

    private static Map<String, Object> pickRequest(UUID requestId, long expectedVersion, UUID lineId,
            String documentReference, long quantity, String sku, String lotCode) {
        return Map.of("requestId", requestId, "expectedVersion", expectedVersion, "lineId", lineId,
                "documentReference", documentReference, "quantity", quantity, "observedSku", sku,
                "observedLotCode", lotCode, "reason", "Counted the identified source line",
                "evidenceReference", "pick-evidence:" + documentReference);
    }

    private static Map<String, Object> dispatchRequest(UUID requestId, long expectedVersion, String documentReference,
            PreparedTransfer transfer, long quantity) {
        return Map.of("requestId", requestId, "expectedVersion", expectedVersion,
                "documentReference", documentReference,
                "lines", java.util.List.of(Map.of("lineId", transfer.lineId(), "quantity", quantity,
                        "observedSku", transfer.sku(), "observedLotCode", transfer.lotCode())),
                "reason", "Confirmed the complete physical handoff",
                "evidenceReference", "dispatch-evidence:" + documentReference);
    }

    private record PreparedTransfer(UUID transferId, UUID lineId, UUID allocationId, long quantity,
            String sku, String lotCode, long sourcePositionVersion) { }

    private String base() {
        return "/api/v1/organizations/" + fixture.organization + "/transfers";
    }

    private Map<String, Object> createRequest(UUID requestId, UUID source, UUID destination,
            UUID position, UUID location, long quantity, long version, String reason) {
        return Map.of("requestId", requestId, "sourceStoreId", source, "destinationStoreId", destination,
                "lines", java.util.List.of(Map.of("sourcePositionId", position, "destinationLocationId", location,
                        "quantity", quantity, "expectedSourceVersion", version)),
                "reason", reason, "evidenceReference", "transfer-review:request");
    }

    private static Map<String, Object> decision(UUID requestId, long version, String reason) {
        return Map.of("requestId", requestId, "expectedVersion", version, "reason", reason,
                "evidenceReference", "transfer-review:decision");
    }

    private long count(String table, UUID organization) {
        return jdbc.sql("SELECT count(*) FROM stockrescue." + table + " WHERE org_id=:org")
                .param("org", organization).query(Long.class).single();
    }

    private JsonNode tree(HttpResponse<String> response) {
        try {
            return json.readTree(response.body());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private JsonNode problem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        var body = tree(response);
        assertThat(body.path("code").asString()).isEqualTo(code);
        return body;
    }

    private static java.util.List<HttpResponse<String>> concurrent(Callable<HttpResponse<String>> first,
            Callable<HttpResponse<String>> second) throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> { start.await(); return first.call(); });
            var two = workers.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return java.util.List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        }
    }

    private final class Fixture {
        private final UUID ownerId = UUID.randomUUID();
        private final String email = ownerId + "@example.test";
        private final UUID organization = UUID.randomUUID();
        private UUID foreignOrganization;
        private final UUID membership = UUID.randomUUID();
        private UUID operatorMembership;
        private final UUID sourceStore = UUID.randomUUID();
        private final UUID destinationStore = UUID.randomUUID();
        private final UUID lane = UUID.randomUUID();
        private final UUID sourceZone = UUID.randomUUID();
        private final UUID destinationZone = UUID.randomUUID();
        private final UUID sourceLocation = UUID.randomUUID();
        private final UUID secondSourceLocation = UUID.randomUUID();
        private final UUID destinationLocation = UUID.randomUUID();
        private final UUID secondDestinationLocation = UUID.randomUUID();
        private final UUID product = UUID.randomUUID();
        private final UUID lot = UUID.randomUUID();
        private final UUID position = UUID.randomUUID();
        private final UUID secondPosition = UUID.randomUUID();

        private Fixture() {
            jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)")
                    .param("id", ownerId).param("email", email).update();
            jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)")
                    .param("id", ownerId).param("hash", passwordHash).update();
            jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Transfer workflow',:owner)")
                    .param("id", organization).param("owner", ownerId).update();
            jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:owner,'ORG_ADMIN')")
                    .param("id", membership).param("org", organization).param("owner", ownerId).update();
            jdbc.sql("""
                    INSERT INTO stockrescue.store(id,org_id,code,name,address_line1,city,postal_code,country_code,zone_id)
                    VALUES (:source,:org,:sourceCode,'Source','Street 1','Warsaw','00-001','PL','Europe/Warsaw'),
                           (:destination,:org,:destinationCode,'Destination','Street 2','Warsaw','00-002','PL','Europe/Warsaw')
                    """)
                    .param("source", sourceStore).param("org", organization).param("sourceCode", sourceStore.toString().replace("-", ""))
                    .param("destination", destinationStore).param("destinationCode", destinationStore.toString().replace("-", "")).update();
            jdbc.sql("""
                    INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES
                    (:sourceZone,:org,:source,'Dry','AMBIENT'),(:destinationZone,:org,:destination,'Dry','AMBIENT')
                    """)
                    .param("sourceZone", sourceZone).param("destinationZone", destinationZone)
                    .param("org", organization).param("source", sourceStore).param("destination", destinationStore).update();
            jdbc.sql("""
                    INSERT INTO stockrescue.storage_location(id,org_id,store_id,zone_id,code,name) VALUES
                    (:sourceLocation,:org,:source,:sourceZone,'SOURCE-A','Source shelf'),
                    (:secondSourceLocation,:org,:source,:sourceZone,'SOURCE-B','Second source shelf'),
                    (:destinationLocation,:org,:destination,:destinationZone,'DEST-A','Destination shelf'),
                    (:secondDestinationLocation,:org,:destination,:destinationZone,'DEST-B','Second destination shelf')
                    """)
                    .param("sourceLocation", sourceLocation).param("secondSourceLocation", secondSourceLocation)
                    .param("destinationLocation", destinationLocation).param("secondDestinationLocation", secondDestinationLocation)
                    .param("org", organization).param("source", sourceStore).param("destination", destinationStore)
                    .param("sourceZone", sourceZone).param("destinationZone", destinationZone).update();
            jdbc.sql("INSERT INTO stockrescue.transport_lane(id,org_id,origin_store_id,destination_store_id,origin_zone_id,destination_zone_id) VALUES (:id,:org,:source,:destination,:sourceZone,:destinationZone)")
                    .param("id", lane).param("org", organization).param("source", sourceStore).param("destination", destinationStore)
                    .param("sourceZone", sourceZone).param("destinationZone", destinationZone).update();
            withFixtureCorrelation(() -> laneService.revise(ownerId, organization, lane, 0, 0,
                    "12.0000", "EUR", 45, null, "Configured the active source route"));
            jdbc.sql("INSERT INTO stockrescue.product(id,org_id,sku,name,base_unit,temperature_class,category) VALUES (:id,:org,'BREAD','Bread','EA','AMBIENT','BAKERY')")
                    .param("id", product).param("org", organization).update();
            withFixtureCorrelation(() -> classification.confirm(ownerId, organization, product,
                    new FoodSafetyProductClassificationConfirmationSubmission(UUID.randomUUID(), 0,
                            ProductCategory.BAKERY, TemperatureClass.AMBIENT,
                            ProductProfile.PACKAGED_SHELF_STABLE_DRY_BAKERY, SourceType.ORIGINAL_PACKAGE,
                            "package:bread-label", "Reviewed the product storage label")));
            jdbc.sql("""
                    INSERT INTO stockrescue.manufacturer_lot(id,org_id,product_id,lot_code,identity_state,
                        manufacturer_identity_key,manufacturer_source,manufacturer_reference,confirmed_by_membership_id,confirmed_at,
                        labeled_value,date_marking_type,date_precision,marking_source,marking_reference,recall_state,damage_state,status_source,status_reference)
                    VALUES (:id,:org,:product,'LOT-A','CONFIRMED','maker:bread-lot','Manufacturer register','register-record',:membership,clock_timestamp(),
                        '2027-11-30','USE_BY','DAY','Original package','label-photo','NOT_REPORTED','NOT_REPORTED','Manufacturer notice','notice-record')
                    """)
                    .param("id", lot).param("org", organization).param("product", product).param("membership", membership).update();
            jdbc.sql("INSERT INTO stockrescue.stock_position(id,org_id,store_id,location_id,product_id,lot_id) VALUES (:id,:org,:store,:location,:product,:lot),(:secondId,:org,:store,:secondLocation,:product,:lot)")
                    .param("id", position).param("org", organization).param("store", sourceStore)
                    .param("location", sourceLocation).param("secondId", secondPosition).param("secondLocation", secondSourceLocation)
                    .param("product", product).param("lot", lot).update();
        }

        private long seedSource(long quantity) {
            return seedSource(sourceLocation, quantity);
        }

        private long seedSource(UUID location, long quantity) {
            return withFixtureCorrelation(() -> inventory.receiveGoods(ownerId, organization, sourceStore,
                    receipt(location, quantity))).positionVersion();
        }

        private long seedDestination(long quantity) {
            return withFixtureCorrelation(() -> inventory.receiveGoods(ownerId, organization, destinationStore,
                    receipt(destinationLocation, quantity))).positionVersion();
        }

        private UUID stockPosition(UUID store, UUID location) {
            return jdbc.sql("SELECT id FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND location_id=:location AND lot_id=:lot")
                    .param("org", organization).param("store", store).param("location", location).param("lot", lot)
                    .query(UUID.class).single();
        }

        private void addReverseLane() {
            UUID reverseLane = UUID.randomUUID();
            jdbc.sql("INSERT INTO stockrescue.transport_lane(id,org_id,origin_store_id,destination_store_id,origin_zone_id,destination_zone_id) VALUES (:id,:org,:source,:destination,:sourceZone,:destinationZone)")
                    .param("id", reverseLane).param("org", organization).param("source", destinationStore)
                    .param("destination", sourceStore).param("sourceZone", destinationZone)
                    .param("destinationZone", sourceZone).update();
            withFixtureCorrelation(() -> laneService.revise(ownerId, organization, reverseLane, 0, 0,
                    "12.0000", "EUR", 45, null, "Configured the reverse transport route"));
        }

        private void revokeGrant(UUID member, UUID store) {
            int changed = jdbc.sql("UPDATE stockrescue.store_grant SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE org_id=:org AND membership_id=:membership AND store_id=:store AND state='ACTIVE'")
                    .param("org", organization).param("membership", member).param("store", store).update();
            assertThat(changed).isEqualTo(1);
        }

        private Browser separateOrganizationAdmin() {
            UUID foreignUser = UUID.randomUUID();
            String foreignEmail = foreignUser + "@example.test";
            foreignOrganization = UUID.randomUUID();
            jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)")
                    .param("id", foreignUser).param("email", foreignEmail).update();
            jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)")
                    .param("id", foreignUser).param("hash", passwordHash).update();
            jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Separate transfer tenant',:owner)")
                    .param("id", foreignOrganization).param("owner", foreignUser).update();
            jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:owner,'ORG_ADMIN')")
                    .param("id", UUID.randomUUID()).param("org", foreignOrganization).param("owner", foreignUser).update();
            return new Browser(foreignEmail);
        }

        private ReceiveGoods receipt(long quantity) {
            return receipt(sourceLocation, quantity);
        }

        private ReceiveGoods receipt(UUID location, long quantity) {
            return new ReceiveGoods(UUID.randomUUID(), new PositionReference(location, product, lot), quantity,
                    "supplier:registered-1", "DELIVERY", "transfer-seed:" + UUID.randomUUID(), "1",
                    Instant.now().minusSeconds(3600), "Counted physical delivery", "signed-delivery-note", null);
        }

        private <T> T withFixtureCorrelation(java.util.function.Supplier<T> operation) {
            String previous = MDC.get("correlationId");
            MDC.put("correlationId", "transfer-workflow-fixture-" + UUID.randomUUID());
            try {
                return operation.get();
            } finally {
                if (previous == null) MDC.remove("correlationId");
                else MDC.put("correlationId", previous);
            }
        }

        private Browser owner() {
            return new Browser(email);
        }

        private Browser operator() {
            return staff("INVENTORY_OPERATOR", java.util.List.of(sourceStore));
        }

        private Browser destinationOperator() {
            return staff("INVENTORY_OPERATOR", java.util.List.of(destinationStore));
        }

        private Browser storeManager() {
            return staff("STORE_MANAGER", java.util.List.of(sourceStore, destinationStore));
        }

        private Browser staff(String role, java.util.List<UUID> stores) {
            UUID operatorId = UUID.randomUUID();
            String operatorEmail = operatorId + "@example.test";
            jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)")
                    .param("id", operatorId).param("email", operatorEmail).update();
            jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)")
                    .param("id", operatorId).param("hash", passwordHash).update();
            operatorMembership = UUID.randomUUID();
            jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:user,'MEMBER')")
                    .param("id", operatorMembership).param("org", organization).param("user", operatorId).update();
            for (UUID store : stores) {
                jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:membership,:store,:role)")
                        .param("id", UUID.randomUUID()).param("org", organization).param("membership", operatorMembership)
                        .param("store", store).param("role", role).update();
            }
            return new Browser(operatorEmail);
        }

        private final class Browser {
            private final String accountEmail;
            private final HttpClient client = HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();

            private Browser(String accountEmail) {
                this.accountEmail = accountEmail;
                var csrf = tree(send("GET", "/api/v1/auth/csrf", null, null)).path("token").asString();
                var response = send("POST", "/api/v1/auth/sign-in", Map.of("email", accountEmail, "password", PASSWORD), csrf);
                if (response.statusCode() != 200) throw new IllegalStateException("Test owner sign-in failed: " + response.body());
            }

            private HttpResponse<String> get(String path) {
                return send("GET", path, null, null);
            }

            private HttpResponse<String> write(String method, String path, Object body) {
                var csrf = tree(get("/api/v1/auth/csrf")).path("token").asString();
                return send(method, path, body, csrf);
            }

            private HttpResponse<String> send(String method, String path, Object body, String csrf) {
                try {
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
                    if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
                    var payload = body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
                    return client.send(request.method(method, payload).build(), HttpResponse.BodyHandlers.ofString());
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
        }
    }
}
