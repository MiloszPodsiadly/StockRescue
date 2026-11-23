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
import java.util.concurrent.ConcurrentHashMap;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
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
import com.stockrescue.inventory.messaging.InventoryChangeListener;
import com.stockrescue.inventory.messaging.InventoryEventCodec;
import com.stockrescue.inventory.messaging.InventoryOutboxRelay;
import com.stockrescue.inventory.persistence.InventoryOutboxRepository;
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
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.kafka.support.Acknowledgment;
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
    @MockitoSpyBean private AccessPolicy accessPolicy;
    @Autowired private InventoryService inventory;
    @Autowired private InventoryChangeListener changeListener;
    @Autowired private InventoryOutboxRepository inventoryOutbox;
    @Autowired private InventoryEventCodec inventoryEventCodec;
    @Autowired private FoodSafetyProductClassificationConfirmationService classification;
    @MockitoSpyBean private LaneService laneService;
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
        assertTransferEvent(transferId, 0, "TransferCommandRecorded", "CREATE", "REQUESTED");
        assertTransferEvent(transferId, 1, "TransferCommandRecorded", "CANCEL", "CANCELLED");
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
    void transferQueuePagesPastOneHundredRequestsWithoutMissingOrDuplicatingItems() throws Exception {
        var browser = fixture.owner();
        var createdIds = new java.util.ArrayList<String>();
        for (int index = 0; index < 101; index++) {
            var created = browser.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                    fixture.destinationStore, fixture.position, fixture.destinationLocation, 1, 0,
                    "Pagination request " + index));
            assertThat(created.statusCode()).as("request %s: %s", index, created.body()).isEqualTo(200);
            createdIds.add(tree(created).path("transferId").asString());
        }

        var first = tree(browser.get(base() + "?storeId=" + fixture.sourceStore + "&offset=0&limit=50"));
        var second = tree(browser.get(base() + "?storeId=" + fixture.sourceStore + "&offset=50&limit=50"));
        var third = tree(browser.get(base() + "?storeId=" + fixture.sourceStore + "&offset=100&limit=50"));
        assertThat(first.path("items")).hasSize(50);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(second.path("items")).hasSize(50);
        assertThat(second.path("hasMore").asBoolean()).isTrue();
        assertThat(third.path("items")).hasSize(1);
        assertThat(third.path("hasMore").asBoolean()).isFalse();
        var pagedIds = java.util.stream.Stream.of(first, second, third)
                .flatMap(page -> java.util.stream.StreamSupport.stream(page.path("items").spliterator(), false))
                .map(item -> item.path("id").asString()).toList();
        assertThat(pagedIds).hasSize(101).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(createdIds);
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
        assertTransferEvent(transferId, 0, "TransferCommandRecorded", "CREATE", "REQUESTED");
        assertTransferEvent(transferId, 1, "TransferCommandRecorded", "REJECT", "REJECTED");
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
        assertThat(jdbc.sql("SELECT on_hand FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(Long.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT state FROM stockrescue.stock_allocation WHERE org_id=:org AND store_id=:store AND id=:allocation")
                .param("org", fixture.organization).param("store", fixture.sourceStore).param("allocation", allocationId)
                .query(String.class).single()).isEqualTo("RELEASED");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_RESERVE'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_RELEASE'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_OUT'")
                .param("org", fixture.organization).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND resource_type='transfer' AND resource_id=:transfer")
                .param("org", fixture.organization).param("transfer", transferId).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void pickingCancellationRequiresPutBackEvidenceAndReleasesOnlyTheOwnedReservationsOnce() throws Exception {
        var browser = fixture.owner();
        long firstSourceVersion = fixture.seedSource(fixture.sourceLocation, 6);
        long secondSourceVersion = fixture.seedSource(fixture.secondSourceLocation, 7);
        var created = browser.write("POST", base(), Map.of("requestId", UUID.randomUUID(),
                "sourceStoreId", fixture.sourceStore, "destinationStoreId", fixture.destinationStore,
                "lines", java.util.List.of(
                        Map.of("sourcePositionId", fixture.position, "destinationLocationId", fixture.destinationLocation,
                                "quantity", 3, "expectedSourceVersion", firstSourceVersion),
                        Map.of("sourcePositionId", fixture.secondPosition, "destinationLocationId", fixture.secondDestinationLocation,
                                "quantity", 4, "expectedSourceVersion", secondSourceVersion)),
                "reason", "Move two independently counted lots", "evidenceReference", "transfer:two-lines-cancel"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(created).path("transferId").asString());
        assertThat(browser.write("POST", base() + "/" + transferId + "/approve",
                decision(UUID.randomUUID(), 0, "Reserve both source lines")).statusCode()).isEqualTo(200);
        assertThat(browser.write("POST", base() + "/" + transferId + "/start-pick",
                decision(UUID.randomUUID(), 1, "Start picking both source lines")).statusCode()).isEqualTo(200);
        var lines = tree(browser.get(base() + "/" + transferId)).path("lines");
        var pickedLine = lines.get(0);
        UUID pickedLineId = UUID.fromString(pickedLine.path("id").asString());
        var pick = browser.write("POST", base() + "/" + transferId + "/pick",
                pickRequest(UUID.randomUUID(), 2, pickedLineId, "pick-cancel-" + UUID.randomUUID(),
                        pickedLine.path("requestedQuantity").asLong(), pickedLine.path("sku").asString(),
                        pickedLine.path("lotCode").asString()));
        assertThat(pick.statusCode()).as(pick.body()).isEqualTo(200);
        assertThat(tree(pick).path("status").asString()).isEqualTo("PICKING");

        var missingPutBack = browser.write("POST", base() + "/" + transferId + "/cancel",
                decision(UUID.randomUUID(), 3, "Return the picked line before cancellation"));
        problem(missingPutBack, 422, "INVALID_REQUEST");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", transferId).query(String.class).single()).isEqualTo("PICKING");
        assertThat(jdbc.sql("SELECT reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(Long.class).single()).isEqualTo(3);

        UUID cancellationId = UUID.randomUUID();
        var cancelBody = cancelRequest(cancellationId, 3, "Return the picked line before cancellation", "put-back-picking-01");
        var committed = browser.write("POST", base() + "/" + transferId + "/cancel", cancelBody);
        assertThat(committed.statusCode()).as(committed.body()).isEqualTo(200);
        // Treat the first response as lost; recovery and the exact same request must return the committed outcome.
        var recovered = tree(browser.get(base() + "/commands/CANCEL/" + cancellationId)).path("outcome");
        var retry = browser.write("POST", base() + "/" + transferId + "/cancel", cancelBody);
        assertThat(tree(retry)).isEqualTo(recovered);
        assertThat(tree(retry).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(String.class).single()).isEqualTo("6:0");
        assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.secondPosition).query(String.class).single()).isEqualTo("7:0");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_RELEASE'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_OUT'")
                .param("org", fixture.organization).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT a.state FROM stockrescue.transfer_line l JOIN stockrescue.stock_allocation a ON a.org_id=l.org_id AND a.store_id=l.source_store_id AND a.id=l.reservation_allocation_id WHERE l.org_id=:org AND l.transfer_id=:transfer")
                .param("org", fixture.organization).param("transfer", transferId)
                .query(String.class).list()).containsOnly("RELEASED");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document_line WHERE org_id=:org AND transfer_id=:transfer AND document_type='PICK'")
                .param("org", fixture.organization).param("transfer", transferId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document_line WHERE org_id=:org AND transfer_id=:transfer AND document_type='PUT_BACK'")
                .param("org", fixture.organization).param("transfer", transferId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT disposition FROM stockrescue.transfer_physical_document_line WHERE org_id=:org AND transfer_id=:transfer AND document_type='PUT_BACK'")
                .param("org", fixture.organization).param("transfer", transferId).query(String.class).single()).isEqualTo("AVAILABLE");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND transfer_id=:transfer AND operation='CANCEL'")
                .param("org", fixture.organization).param("transfer", transferId).query(Long.class).single()).isEqualTo(1);
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
    void opposingReceiptsAndReturnsShareParticipantLocksWithoutDeadlock() throws Exception {
        var forwardOperator=fixture.owner();
        var reverseOperator=fixture.owner();
        fixture.addReverseLane();
        long sourceVersion=fixture.seedSource(6);
        var forward=preparePickedTransfer(forwardOperator,fixture.sourceStore,fixture.destinationStore,fixture.position,
                fixture.destinationLocation,2,sourceVersion);
        long reverseSourceVersion=fixture.seedDestination(6);
        UUID reverseSourcePosition=fixture.stockPosition(fixture.destinationStore,fixture.destinationLocation);
        var reverse=preparePickedTransfer(reverseOperator,fixture.destinationStore,fixture.sourceStore,reverseSourcePosition,
                fixture.sourceLocation,2,reverseSourceVersion);
        dispatch(forwardOperator,forward,3,"dispatch-opposing-receive-forward");
        dispatch(reverseOperator,reverse,3,"dispatch-opposing-receive-reverse");

        UUID forwardDestinationPosition=fixture.stockPosition(fixture.destinationStore,fixture.destinationLocation);
        UUID reverseDestinationPosition=fixture.position;
        var receipts=withSettlementAuthorizationBarrier(
                () -> forwardOperator.write("POST",base()+"/"+forward.transferId()+"/receive",
                        receiveRequest(forward,4,stockVersion(fixture.destinationStore,forwardDestinationPosition),1,"receive-opposing-forward")),
                () -> reverseOperator.write("POST",base()+"/"+reverse.transferId()+"/receive",
                        receiveRequest(reverse,4,stockVersion(fixture.sourceStore,reverseDestinationPosition),1,"receive-opposing-reverse")));
        assertThat(receipts).allSatisfy(response -> assertThat(response.statusCode()).as(response.body()).isEqualTo(200));
        assertThat(custody(forward.transferId(),forward.lineId())).isEqualTo("2:1:0:0:1");
        assertThat(custody(reverse.transferId(),reverse.lineId())).isEqualTo("2:1:0:0:1");

        var forwardReturnRequest=forwardOperator.write("POST",base()+"/"+forward.transferId()+"/request-return",Map.of(
                "requestId",UUID.randomUUID(),"expectedVersion",5,"requestReference","opposing-forward-"+UUID.randomUUID(),
                "lines",java.util.List.of(Map.of("lineId",forward.lineId())),"reason","Carrier returned the remaining line",
                "evidenceReference","carrier:opposing-forward"));
        var reverseReturnRequest=reverseOperator.write("POST",base()+"/"+reverse.transferId()+"/request-return",Map.of(
                "requestId",UUID.randomUUID(),"expectedVersion",5,"requestReference","opposing-reverse-"+UUID.randomUUID(),
                "lines",java.util.List.of(Map.of("lineId",reverse.lineId())),"reason","Carrier returned the remaining line",
                "evidenceReference","carrier:opposing-reverse"));
        assertThat(forwardReturnRequest.statusCode()).as(forwardReturnRequest.body()).isEqualTo(200);
        assertThat(reverseReturnRequest.statusCode()).as(reverseReturnRequest.body()).isEqualTo(200);
        var forwardReference=jdbc.sql("SELECT observed_state->>'requestReference' FROM stockrescue.transfer_reconciliation_evidence WHERE org_id=:org AND command_id=:command AND evidence_type='RETURN_REQUEST'")
                .param("org",fixture.organization).param("command",UUID.fromString(tree(forwardReturnRequest).path("commandId").asString()))
                .query(String.class).single();
        var reverseReference=jdbc.sql("SELECT observed_state->>'requestReference' FROM stockrescue.transfer_reconciliation_evidence WHERE org_id=:org AND command_id=:command AND evidence_type='RETURN_REQUEST'")
                .param("org",fixture.organization).param("command",UUID.fromString(tree(reverseReturnRequest).path("commandId").asString()))
                .query(String.class).single();
        var returns=withSettlementAuthorizationBarrier(
                () -> forwardOperator.write("POST",base()+"/"+forward.transferId()+"/confirm-return",
                        confirmReturnRequest(UUID.randomUUID(),6,forwardReference,"return-opposing-forward",forward.lineId(),
                                stockVersion(fixture.sourceStore,fixture.position),"return-forward-line",forward.sku(),forward.lotCode())),
                () -> reverseOperator.write("POST",base()+"/"+reverse.transferId()+"/confirm-return",
                        confirmReturnRequest(UUID.randomUUID(),6,reverseReference,"return-opposing-reverse",reverse.lineId(),
                                stockVersion(fixture.destinationStore,reverseSourcePosition),"return-reverse-line",reverse.sku(),reverse.lotCode())));
        assertThat(returns).allSatisfy(response -> assertThat(response.statusCode()).as(response.body()).isEqualTo(200));
        assertThat(custody(forward.transferId(),forward.lineId())).isEqualTo("2:1:0:1:0");
        assertThat(custody(reverse.transferId(),reverse.lineId())).isEqualTo("2:1:0:1:0");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND operation='RECEIVE' AND transfer_id IN (:forward,:reverse)")
                .param("org",fixture.organization).param("forward",forward.transferId()).param("reverse",reverse.transferId())
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND operation='CONFIRM_RETURN' AND transfer_id IN (:forward,:reverse)")
                .param("org",fixture.organization).param("forward",forward.transferId()).param("reverse",reverse.transferId())
                .query(Long.class).single()).isEqualTo(2);
        for(var transferId:java.util.List.of(forward.transferId(),reverse.transferId())) {
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document WHERE org_id=:org AND transfer_id=:transfer AND document_type='RECEIPT'")
                    .param("org",fixture.organization).param("transfer",transferId).query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document WHERE org_id=:org AND transfer_id=:transfer AND document_type='RETURN'")
                    .param("org",fixture.organization).param("transfer",transferId).query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_outbox_event WHERE org_id=:org AND transfer_id=:transfer AND event_type='TransferReceived'")
                    .param("org",fixture.organization).param("transfer",transferId).query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_outbox_event WHERE org_id=:org AND transfer_id=:transfer AND event_type='TransferReturned'")
                    .param("org",fixture.organization).param("transfer",transferId).query(Long.class).single()).isEqualTo(1);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.inventory_outbox_event e JOIN stockrescue.stock_movement m ON m.org_id=e.org_id AND m.store_id=e.store_id AND m.id=e.movement_id WHERE m.org_id=:org AND m.operation='TRANSFER_IN'")
                .param("org",fixture.organization).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.inventory_outbox_event e JOIN stockrescue.stock_movement m ON m.org_id=e.org_id AND m.store_id=e.store_id AND m.id=e.movement_id WHERE m.org_id=:org AND m.operation='TRANSFER_RETURN'")
                .param("org",fixture.organization).query(Long.class).single()).isEqualTo(2);
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
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single()).isEqualTo("IN_TRANSIT");
        assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(String.class).single()).isEqualTo("3:0");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_RELEASE'")
                .param("org", fixture.organization).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_OUT'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void cancellationAndDispatchRaceHasOnePreDispatchOrInTransitWinner() throws Exception {
        var canceller = fixture.owner();
        var dispatcher = fixture.owner();
        var prepared = preparePickedTransfer(canceller, 2);
        var cancellation = cancelRequest(UUID.randomUUID(), 3, "Cancel before dispatch", "put-back-dispatch-race");
        var dispatch = dispatchRequest(UUID.randomUUID(), 3, "dispatch-cancel-race", prepared, prepared.quantity());

        var outcomes = concurrent(
                () -> canceller.write("POST", base() + "/" + prepared.transferId() + "/cancel", cancellation),
                () -> dispatcher.write("POST", base() + "/" + prepared.transferId() + "/dispatch", dispatch));
        assertThat(outcomes.stream().filter(response -> response.statusCode() == 200)).hasSize(1);
        assertThat(outcomes.stream().filter(response -> response.statusCode() == 409)).hasSize(1);
        String status = jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single();
        assertThat(status).isIn("CANCELLED", "IN_TRANSIT");
        if (status.equals("CANCELLED")) {
            assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                    .param("org", fixture.organization).param("position", fixture.position).query(String.class).single()).isEqualTo("5:0");
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_RELEASE'")
                    .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_OUT'")
                    .param("org", fixture.organization).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document_line WHERE org_id=:org AND transfer_id=:transfer AND document_type='PUT_BACK'")
                    .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isEqualTo(1);
        } else {
            assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                    .param("org", fixture.organization).param("position", fixture.position).query(String.class).single()).isEqualTo("3:0");
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_RELEASE'")
                    .param("org", fixture.organization).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_OUT'")
                    .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document_line WHERE org_id=:org AND transfer_id=:transfer AND document_type='PUT_BACK'")
                    .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isZero();
        }
    }

    @Test
    void cancellationAndPhysicalPickRaceCannotLeaveAnUnreleasedReservation() throws Exception {
        var canceller = fixture.owner();
        var picker = fixture.owner();
        long sourceVersion = fixture.seedSource(5);
        var prepared = preparePickingTransfer(canceller, fixture.position, fixture.destinationLocation, 2, sourceVersion);
        var cancellationId = UUID.randomUUID();
        var cancellation = cancelRequest(cancellationId, 2, "Cancel during source picking", null);
        var pick = pickRequest(UUID.randomUUID(), 2, prepared.lineId(), "pick-cancel-race-" + UUID.randomUUID(),
                prepared.quantity(), prepared.sku(), prepared.lotCode());

        var outcomes = concurrent(
                () -> canceller.write("POST", base() + "/" + prepared.transferId() + "/cancel", cancellation),
                () -> picker.write("POST", base() + "/" + prepared.transferId() + "/pick", pick));
        assertThat(outcomes.stream().filter(response -> response.statusCode() == 200)).hasSize(1);
        assertThat(outcomes.stream().filter(response -> response.statusCode() == 409)).hasSize(1);
        String status = jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single();
        if (status.equals("PICKED")) {
            var finalCancel = canceller.write("POST", base() + "/" + prepared.transferId() + "/cancel",
                    cancelRequest(UUID.randomUUID(), 3, "Cancel after the concurrent pick", "put-back-after-pick-race"));
            assertThat(finalCancel.statusCode()).as(finalCancel.body()).isEqualTo(200);
        } else {
            assertThat(status).isEqualTo("CANCELLED");
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document_line WHERE org_id=:org AND transfer_id=:transfer AND document_type='PICK'")
                    .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isZero();
        }
        assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(String.class).single()).isEqualTo("5:0");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_RELEASE'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_OUT'")
                .param("org", fixture.organization).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single()).isEqualTo("CANCELLED");
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

    @Test
    void sharedKafkaTopicInterleavesInventoryAndTransferEventsWithoutDoubleApplyingStock() throws Exception {
        var browser=fixture.owner();
        var prepared=preparePickedTransfer(browser,2);
        dispatch(browser,prepared,3,"dispatch-shared-topic-route");
        var received=browser.write("POST",base()+"/"+prepared.transferId()+"/receive",
                receiveRequest(prepared,4,0,2,"receipt-shared-topic-route"));
        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);

        UUID inventoryEventId=jdbc.sql("SELECT id FROM stockrescue.inventory_outbox_event WHERE org_id=:org AND position_id=:position ORDER BY aggregate_version LIMIT 1")
                .param("org",fixture.organization).param("position",fixture.position).query(UUID.class).single();
        var inventoryEvent=inventoryEventCodec.fromOutbox(inventoryOutbox.find(inventoryEventId));
        var transferEvents=jdbc.sql("SELECT id FROM stockrescue.transfer_outbox_event WHERE org_id=:org AND transfer_id=:transfer ORDER BY aggregate_version")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).query(UUID.class).list();
        assertThat(transferEvents).hasSize(6);

        long stockMovementsBefore=jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org")
                .param("org",fixture.organization).query(Long.class).single();
        var firstTransfer=transferEventCodec.fromOutbox(transferOutbox.find(transferEvents.getFirst()));
        consumeSharedTopicRecord(0,prepared.transferId().toString(),transferEventCodec.encode(firstTransfer));
        String inventoryKey=inventoryEvent.tenantId()+":"+inventoryEvent.aggregateType()+":"+inventoryEvent.aggregateId();
        consumeSharedTopicRecord(1,inventoryKey,inventoryEventCodec.encode(inventoryEvent));
        for(int index=1;index<transferEvents.size();index++) {
            var event=transferEventCodec.fromOutbox(transferOutbox.find(transferEvents.get(index)));
            consumeSharedTopicRecord(index+1,prepared.transferId().toString(),transferEventCodec.encode(event));
        }

        UUID dispatched=transferEventId(prepared.transferId(),4);
        UUID receivedEvent=transferEventId(prepared.transferId(),5);
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_change_projection WHERE consumer_name='transfer-change-projection-v1' AND org_id=:org AND transfer_id=:transfer")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).query(String.class).single()).isEqualTo("RECEIVED");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_inbox WHERE consumer_name='transfer-change-projection-v1' AND event_id IN (:dispatched,:received) AND applied_at IS NOT NULL")
                .param("dispatched",dispatched).param("received",receivedEvent).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.inventory_change_feed WHERE consumer_name='inventory-change-projection-v1' AND event_id=:event")
                .param("event",inventoryEvent.eventId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.inventory_inbox WHERE consumer_name='inventory-change-projection-v1' AND event_id=:event")
                .param("event",inventoryEvent.eventId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org")
                .param("org",fixture.organization).query(Long.class).single()).isEqualTo(stockMovementsBefore);
    }

    private void consumeSharedTopicRecord(long offset,String key,String payload) {
        var acknowledgment=org.mockito.Mockito.mock(Acknowledgment.class);
        changeListener.consume(new ConsumerRecord<>("stockrescue.inventory.events.v1",0,9_000_000_000L+offset,key,payload),acknowledgment);
        org.mockito.Mockito.verify(acknowledgment).acknowledge();
    }

    private UUID transferEventId(UUID transferId,long version) {
        return jdbc.sql("SELECT id FROM stockrescue.transfer_outbox_event WHERE org_id=:org AND transfer_id=:transfer AND aggregate_version=:version")
                .param("org",fixture.organization).param("transfer",transferId).param("version",version).query(UUID.class).single();
    }

    private void assertTransferEvent(UUID transferId,long version,String type,String operation,String statusAfter) {
        var event=transferEventCodec.fromOutbox(transferOutbox.find(transferEventId(transferId,version)));
        assertThat(event.eventType()).isEqualTo(type);
        assertThat(event.data().operation()).isEqualTo(operation);
        assertThat(event.data().statusAfter()).isEqualTo(statusAfter);
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
        var opened = manager.write("POST", base() + "/" + prepared.transferId() + "/open-discrepancy",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 5, "lineId", prepared.lineId(),
                        "expectedState", "Eighteen units received", "observedState", "Carrier reports two missing units",
                        "reason", "Review the carrier's documented shortage", "evidenceReference", "carrier:shortage-20"));
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);
        assertThat(tree(opened).path("version").asLong()).isEqualTo(6);
        assertTransferEvent(prepared.transferId(), 6, "TransferCommandRecorded", "OPEN_DISCREPANCY", "RECONCILIATION_REQUIRED");
        problem(manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy",
                decision(UUID.randomUUID(), 6, "Cannot close while two units remain in transit")),
                409, "INVENTORY_CONFLICT");

        var damaged = manager.write("POST", base() + "/" + prepared.transferId() + "/damage",
                damageRequest(prepared, 6, 2, "damage-proof-2"));
        assertThat(damaged.statusCode()).as(damaged.body()).isEqualTo(200);
        assertThat(tree(damaged).path("status").asString()).isEqualTo("RECONCILIATION_REQUIRED");
        assertTransferEvent(prepared.transferId(), 7, "TransferLossConfirmed", "CONFIRM_LOSS", "RECONCILIATION_REQUIRED");
        assertThat(tree(damaged).path("stockMovementId").isNull()).isTrue();
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(28);
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("20:18:2:0:0");

        String lossPayload=transferEventCodec.encode(transferEventCodec.fromOutbox(
                transferOutbox.find(transferEventId(prepared.transferId(), 7))));

        var closeRequest = decision(UUID.randomUUID(), 7, "Close fully documented custody discrepancy");
        var closed = manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy", closeRequest);
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        assertThat(tree(closed).path("status").asString()).isEqualTo("CLOSED_WITH_DISCREPANCY");
        assertTransferEvent(prepared.transferId(), 8, "TransferDiscrepancyResolved", "CLOSE", "CLOSED_WITH_DISCREPANCY");
        String closurePayload=transferEventCodec.encode(transferEventCodec.fromOutbox(
                transferOutbox.find(transferEventId(prepared.transferId(), 8))));
        consumeSharedTopicRecord(100, prepared.transferId().toString(), closurePayload);
        consumeSharedTopicRecord(101, prepared.transferId().toString(), lossPayload);
        for(long version=0;version<=6;version++) {
            var event=transferEventCodec.fromOutbox(transferOutbox.find(transferEventId(prepared.transferId(), version)));
            consumeSharedTopicRecord(102+version, prepared.transferId().toString(), transferEventCodec.encode(event));
        }
        assertThat(jdbc.sql("SELECT aggregate_version || ':' || status FROM stockrescue.transfer_change_projection "
                        + "WHERE consumer_name='transfer-change-projection-v1' AND org_id=:org AND transfer_id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single())
                .isEqualTo("8:CLOSED_WITH_DISCREPANCY");
        consumeSharedTopicRecord(110, prepared.transferId().toString(), lossPayload);
        consumeSharedTopicRecord(111, prepared.transferId().toString(), closurePayload);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_inbox WHERE consumer_name='transfer-change-projection-v1' "
                        + "AND org_id=:org AND transfer_id=:transfer AND applied_at IS NOT NULL")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isEqualTo(9);
        assertThat(tree(manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy", closeRequest)))
                .isEqualTo(tree(closed));
        problem(manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy",
                decision(UUID.randomUUID(), 8, "A closed transfer cannot be closed again")), 409, "INVENTORY_CONFLICT");
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
    void transferCommandIntentPersistsExactActorScopedPayloadAndRechecksPermissionBeforeSubmission() throws Exception {
        var manager = fixture.storeManager();
        UUID managerMembership = fixture.operatorMembership;
        var prepared = preparePickedTransfer(manager, 2);
        String intentsPath = "/api/v1/organizations/" + fixture.organization + "/transfer-command-intents";
        UUID requestId = UUID.randomUUID();
        UUID prepareId = UUID.randomUUID();
        var payload = decision(requestId, 3, "Close the documented reconciliation after review");
        var prepare = Map.of("prepareId", prepareId, "operation", "CLOSE", "transferId", prepared.transferId(), "payload", payload);

        var first = manager.write("POST", intentsPath, prepare);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        var summary = tree(first);
        assertThat(summary.path("state").asString()).isEqualTo("PREPARED");
        UUID intentId = UUID.fromString(summary.path("id").asString());
        assertThat(tree(manager.write("POST", intentsPath, prepare)).path("id").asString()).isEqualTo(intentId.toString());

        var reorderedPayload = new java.util.LinkedHashMap<String, Object>();
        payload.keySet().stream().sorted(java.util.Comparator.reverseOrder())
                .forEach(key -> reorderedPayload.put(key, payload.get(key)));
        var semanticallySame = manager.write("POST", intentsPath, Map.of("prepareId", prepareId, "operation", "CLOSE",
                "transferId", prepared.transferId(), "payload", reorderedPayload));
        assertThat(semanticallySame.statusCode()).as(semanticallySame.body()).isEqualTo(200);
        assertThat(tree(semanticallySame).path("id").asString()).isEqualTo(intentId.toString());

        var changedPayload = new java.util.HashMap<String, Object>(payload);
        changedPayload.put("reason", "Changed command details must conflict under the same request key");
        problem(manager.write("POST", intentsPath, Map.of("prepareId", prepareId, "operation", "CLOSE",
                "transferId", prepared.transferId(), "payload", changedPayload)), 409, "IDEMPOTENCY_CONFLICT");

        var operator = fixture.operator();
        var otherActorPending = tree(operator.get(intentsPath));
        assertThat(otherActorPending.path("items")).isEmpty();
        var pending = tree(manager.get(intentsPath));
        assertThat(pending.path("items")).hasSize(1);
        assertThat(pending.path("items").get(0).path("id").asString()).isEqualTo(intentId.toString());

        String intentPath = intentsPath + "/" + intentId;
        var inspection = tree(manager.get(intentPath));
        assertThat(inspection.path("executionStatus").asString()).isEqualTo("PREPARED");
        assertThat(inspection.path("payload").path("requestId").asString()).isEqualTo(requestId.toString());
        var submitted = manager.write("POST", intentPath + "/submitted", null);
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(200);
        assertThat(tree(manager.get(intentPath)).path("executionStatus").asString()).isEqualTo("SUBMITTED_AWAITING_OUTCOME");

        problem(manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy", changedPayload),
                409, "IDEMPOTENCY_CONFLICT");
        var otherTransfer = manager.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.secondPosition, fixture.destinationLocation, 1, 0,
                "Create a separate request for target binding"));
        assertThat(otherTransfer.statusCode()).as(otherTransfer.body()).isEqualTo(200);
        UUID otherTransferId = UUID.fromString(tree(otherTransfer).path("transferId").asString());
        UUID managerId = jdbc.sql("SELECT user_id FROM stockrescue.membership WHERE org_id=:org AND id=:membership")
                .param("org", fixture.organization).param("membership", managerMembership).query(UUID.class).single();
        byte[] intentFingerprint = jdbc.sql("SELECT payload_fingerprint FROM stockrescue.transfer_command_intent WHERE org_id=:org AND id=:intent")
                .param("org", fixture.organization).param("intent", intentId)
                .query((row, index) -> row.getBytes(1)).single();
        rejectPreparedIntentReceipt(prepared.transferId(), managerId, managerMembership, requestId, new byte[32]);
        rejectPreparedIntentReceipt(otherTransferId, managerId, managerMembership, requestId, intentFingerprint);
        problem(manager.write("POST", base() + "/" + otherTransferId + "/close-discrepancy", payload),
                409, "IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single())
                .isEqualTo("PICKED");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", otherTransferId).query(String.class).single())
                .isEqualTo("REQUESTED");

        var failure = manager.write("POST", intentPath + "/resolve-failure", Map.of("resolution", "CONFLICTED"));
        assertThat(failure.statusCode()).as(failure.body()).isEqualTo(200);
        assertThat(tree(failure).path("state").asString()).isEqualTo("CONFLICTED");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND operation='CLOSE' AND request_id=:request")
                .param("org", fixture.organization)
                .param("request", requestId).query(Long.class).single()).isZero();

        UUID staleRequestId = UUID.randomUUID();
        var stalePayload = decision(staleRequestId, 3, "Do not submit after the discrepancy grant is revoked");
        var stalePrepared = manager.write("POST", intentsPath, Map.of("prepareId", UUID.randomUUID(), "operation", "CLOSE",
                "transferId", prepared.transferId(), "payload", stalePayload));
        assertThat(stalePrepared.statusCode()).as(stalePrepared.body()).isEqualTo(200);
        UUID staleIntentId = UUID.fromString(tree(stalePrepared).path("id").asString());
        assertThat(manager.write("POST", intentsPath + "/" + staleIntentId + "/submitted", null).statusCode()).isEqualTo(200);
        fixture.revokeGrant(managerMembership, fixture.sourceStore);
        fixture.revokeGrant(managerMembership, fixture.destinationStore);
        assertThat(manager.write("POST", intentsPath + "/" + staleIntentId + "/submitted", null).statusCode()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT state FROM stockrescue.transfer_command_intent WHERE org_id=:org AND id=:intent")
                .param("org", fixture.organization).param("intent", staleIntentId).query(String.class).single())
                .isEqualTo("SUBMITTED");
        var redactedResponse = manager.get(intentsPath);
        assertThat(redactedResponse.statusCode()).as(redactedResponse.body()).isEqualTo(200);
        var redactedItems = tree(redactedResponse).path("items");
        assertThat(redactedItems).as(redactedResponse.body()).hasSize(1);
        var redactedSummary = redactedItems.get(0);
        assertThat(redactedSummary.path("redacted").asBoolean()).isTrue();
        assertThat(redactedSummary.path("operation").isNull()).isTrue();
        assertThat(redactedSummary.path("transferId").isNull()).isTrue();
        assertThat(redactedSummary.path("requestId").isNull()).isTrue();
        var redactedInspection = tree(manager.get(intentsPath + "/" + staleIntentId));
        assertThat(redactedInspection.path("redacted").asBoolean()).isTrue();
        assertThat(redactedInspection.path("operation").isNull()).isTrue();
        assertThat(redactedInspection.path("transferId").isNull()).isTrue();
        assertThat(redactedInspection.path("requestId").isNull()).isTrue();
        assertThat(redactedInspection.path("payload").isNull()).isTrue();
        assertThat(redactedInspection.path("committedOutcome").isNull()).isTrue();
        assertThat(redactedInspection.path("executionStatus").asString()).isEqualTo("SUBMITTED_AWAITING_OUTCOME");
        var redactedResolution = manager.write("POST", intentsPath + "/" + staleIntentId + "/resolve-failure",
                Map.of("resolution", "REJECTED"));
        assertThat(redactedResolution.statusCode()).as(redactedResolution.body()).isEqualTo(200);
        assertThat(tree(redactedResolution).path("redacted").asBoolean()).isTrue();
        assertThat(tree(manager.get(intentsPath)).path("items")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND operation='CLOSE' AND request_id=:request")
                .param("org", fixture.organization)
                .param("request", staleRequestId).query(Long.class).single()).isZero();
    }

    @Test
    void revokedStoreGrantAllowsOnlyRedactedRecoveryAndAcknowledgementOfCommittedCommand() throws Exception {
        var operator = fixture.operator();
        fixture.seedSource(2);
        UUID requestId = UUID.randomUUID();
        var payload = createRequest(requestId, fixture.sourceStore, fixture.destinationStore,
                fixture.position, fixture.destinationLocation, 1, 1, "Request stock for a store operation");
        String intentsPath = "/api/v1/organizations/" + fixture.organization + "/transfer-command-intents";
        var prepared = operator.write("POST", intentsPath, Map.of("prepareId", UUID.randomUUID(), "operation", "CREATE",
                "payload", payload));
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        UUID intentId = UUID.fromString(tree(prepared).path("id").asString());
        var committed = operator.write("POST", base(), payload);
        assertThat(committed.statusCode()).as(committed.body()).isEqualTo(200);

        fixture.revokeGrant(fixture.operatorMembership, fixture.sourceStore);
        var pending = tree(operator.get(intentsPath)).path("items").get(0);
        assertThat(pending.path("redacted").asBoolean()).isTrue();
        assertThat(pending.path("operation").isNull()).isTrue();
        assertThat(pending.path("transferId").isNull()).isTrue();
        assertThat(pending.path("requestId").isNull()).isTrue();
        var inspectionResponse = operator.get(intentsPath + "/" + intentId);
        assertThat(inspectionResponse.statusCode()).as(inspectionResponse.body()).isEqualTo(200);
        var inspection = tree(inspectionResponse);
        assertThat(inspection.path("redacted").asBoolean()).as(inspection.toPrettyString()).isTrue();
        assertThat(inspection.path("executionStatus").asString()).isEqualTo("COMMITTED_AWAITING_ACKNOWLEDGEMENT");
        assertThat(inspection.path("operation").isNull()).isTrue();
        assertThat(inspection.path("payload").isNull()).isTrue();
        assertThat(inspection.path("committedOutcome").isNull()).isTrue();
        var acknowledged = operator.write("POST", intentsPath + "/" + intentId + "/acknowledge", null);
        assertThat(acknowledged.statusCode()).as(acknowledged.body()).isEqualTo(200);
        assertThat(tree(acknowledged).path("state").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(tree(acknowledged).path("redacted").asBoolean()).isTrue();
        assertThat(tree(operator.get(intentsPath)).path("items")).isEmpty();
    }

    @Test
    void resolvingSubmittedIntentAfterLosingStoreAGrantAllowsACommandAtStoreB() throws Exception {
        var operator = fixture.operator();
        UUID operatorMembership = fixture.operatorMembership;
        jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:membership,:store,'INVENTORY_OPERATOR')")
                .param("id", UUID.randomUUID()).param("org", fixture.organization).param("membership", operatorMembership)
                .param("store", fixture.destinationStore).update();
        fixture.addReverseLane();
        long sourceVersionA = fixture.seedSource(2);
        long sourceVersionB = fixture.seedDestination(2);
        UUID requestIdA = UUID.randomUUID();
        var payloadA = createRequest(requestIdA, fixture.sourceStore, fixture.destinationStore,
                fixture.position, fixture.destinationLocation, 1, sourceVersionA, "Prepare source store A request");
        String intentsPath = "/api/v1/organizations/" + fixture.organization + "/transfer-command-intents";
        var prepared = operator.write("POST", intentsPath, Map.of("prepareId", UUID.randomUUID(), "operation", "CREATE",
                "payload", payloadA));
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        UUID intentId = UUID.fromString(tree(prepared).path("id").asString());
        assertThat(operator.write("POST", intentsPath + "/" + intentId + "/submitted", null).statusCode()).isEqualTo(200);

        fixture.revokeGrant(operatorMembership, fixture.sourceStore);
        problem(operator.get(base() + "/commands/CREATE/" + requestIdA), 404, "RESOURCE_NOT_FOUND");
        var redacted = tree(operator.get(intentsPath + "/" + intentId));
        assertThat(redacted.path("redacted").asBoolean()).isTrue();
        assertThat(redacted.path("executionStatus").asString()).isEqualTo("SUBMITTED_AWAITING_OUTCOME");
        assertThat(redacted.path("payload").isNull()).isTrue();
        var resolved = operator.write("POST", intentsPath + "/" + intentId + "/resolve-failure",
                Map.of("resolution", "CONFLICTED"));
        assertThat(resolved.statusCode()).as(resolved.body()).isEqualTo(200);
        assertThat(tree(operator.get(intentsPath)).path("items")).isEmpty();

        UUID requestIdB = UUID.randomUUID();
        var payloadB = createRequest(requestIdB, fixture.destinationStore, fixture.sourceStore,
                fixture.stockPosition(fixture.destinationStore, fixture.destinationLocation), fixture.sourceLocation,
                1, sourceVersionB, "Create a new request at store B after safe recovery");
        var createdAtB = operator.write("POST", base(), payloadB);
        assertThat(createdAtB.statusCode()).as(createdAtB.body()).isEqualTo(200);
        assertThat(tree(createdAtB).path("status").asString()).isEqualTo("REQUESTED");
        assertThat(jdbc.sql("SELECT state FROM stockrescue.transfer_command_intent WHERE org_id=:org AND id=:intent")
                .param("org", fixture.organization).param("intent", intentId).query(String.class).single()).isEqualTo("CONFLICTED");
    }

    @Test
    void managerMustResolveAnOpenDiscrepancyAfterEveryUnitIsPhysicallyReceived() throws Exception {
        var manager = fixture.storeManager();
        var prepared = preparePickedTransfer(manager, 2);
        dispatch(manager, prepared, 3, "dispatch-clean-resolution");
        var opened = manager.write("POST", base() + "/" + prepared.transferId() + "/open-discrepancy",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 4, "lineId", prepared.lineId(),
                        "expectedState", "Two dispatched units", "observedState", "Carrier evidence requires review",
                        "reason", "Review carrier evidence before settling custody", "evidenceReference", "carrier:review-clean-receipt"));
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);
        assertThat(tree(opened).path("status").asString()).isEqualTo("RECONCILIATION_REQUIRED");

        var received = manager.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 5, 0, 2, "receipt-after-discrepancy"));
        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);
        assertThat(tree(received).path("status").asString()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(tree(received).path("version").asLong()).isEqualTo(6);
        assertTransferEvent(prepared.transferId(), 6, "TransferReceived", "RECEIVE", "RECONCILIATION_REQUIRED");
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("2:2:0:0:0");

        problem(manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy",
                decision(UUID.randomUUID(), 5, "Reject stale reconciliation confirmation")), 409, "INVENTORY_CONFLICT");
        var closeRequest = decision(UUID.randomUUID(), 6, "Resolve the reviewed discrepancy after full receipt");
        var resolved = manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy", closeRequest);
        assertThat(resolved.statusCode()).as(resolved.body()).isEqualTo(200);
        assertThat(tree(resolved).path("status").asString()).isEqualTo("RECEIVED");
        assertThat(tree(resolved).path("version").asLong()).isEqualTo(7);
        assertTransferEvent(prepared.transferId(), 7, "TransferDiscrepancyResolved", "CLOSE", "RECEIVED");
        assertThat(tree(manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy", closeRequest)))
                .isEqualTo(tree(resolved));
        assertThat(jdbc.sql("SELECT observed_state->>'resolvedStatus' FROM stockrescue.transfer_reconciliation_evidence "
                        + "WHERE org_id=:org AND transfer_id=:transfer AND evidence_type='CUSTODY_RECONCILIATION' AND command_id=:command")
                .param("org", fixture.organization).param("transfer", prepared.transferId())
                .param("command", UUID.fromString(tree(resolved).path("commandId").asString()))
                .query(String.class).single()).isEqualTo("RECEIVED");
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
        var transferScopedTransit = receiver.get(base() + "/" + prepared.transferId() + "/in-transit?limit=10");
        assertThat(transferScopedTransit.statusCode()).isEqualTo(200);
        assertThat(tree(transferScopedTransit).path("items")).hasSize(1);
        var transferScopedEvidence = receiver.get(base() + "/" + prepared.transferId() + "/discrepancies?limit=10");
        assertThat(transferScopedEvidence.statusCode()).isEqualTo(200);
        assertThat(tree(transferScopedEvidence).path("items")).isEmpty();

        var response = receiver.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 4, 0, 2, "receipt-destination-operator"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(tree(response).path("status").asString()).isEqualTo("RECEIVED");
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(2);
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("2:2:0:0:0");
    }

    @Test
    void transferCapabilitiesReturnActionsOnlyForTheGrantedParticipantStore() throws Exception {
        var owner = fixture.owner();
        var prepared = preparePickedTransfer(owner, 2);

        var sourceOperator = fixture.operator();
        var source = tree(sourceOperator.get(base() + "/" + prepared.transferId() + "/capabilities"));
        assertThat(source.path("sourceActions").toString()).contains("READ_TRANSFERS", "PICK_TRANSFER", "DISPATCH_TRANSFER");
        assertThat(source.path("destinationActions")).isEmpty();

        var destinationOperator = fixture.destinationOperator();
        var destination = tree(destinationOperator.get(base() + "/" + prepared.transferId() + "/capabilities"));
        assertThat(destination.path("sourceActions")).isEmpty();
        assertThat(destination.path("destinationActions").toString()).contains("READ_TRANSFERS", "RECEIVE_STOCK");
        assertThat(destination.path("destinationActions").toString()).doesNotContain("APPROVE_TRANSFER");
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

        var cancellation = browser.write("POST", base() + "/" + prepared.transferId() + "/cancel",
                cancelRequest(UUID.randomUUID(), 3, "Route revision prevents dispatch", "put-back-after-route-revision"));
        assertThat(cancellation.statusCode()).as(cancellation.body()).isEqualTo(200);
        assertThat(tree(cancellation).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT on_hand || ':' || reserved FROM stockrescue.stock_position WHERE org_id=:org AND id=:position")
                .param("org", fixture.organization).param("position", fixture.position).query(String.class).single()).isEqualTo("5:0");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_RELEASE'")
                .param("org", fixture.organization).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document_line WHERE org_id=:org AND transfer_id=:transfer AND document_type='PUT_BACK'")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isEqualTo(1);
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
    void sourceWithAnOpenTransferCannotBeArchivedAndPhysicalReceiptCanComplete() throws Exception {
        var owner = fixture.owner();
        var prepared = preparePickedTransfer(owner, 2);
        dispatch(owner, prepared, 3, "dispatch-after-source-archive");
        var archived = owner.write("POST", "/api/v1/organizations/" + fixture.organization + "/stores/"
                + fixture.sourceStore + "/archive", Map.of("version", 0, "reason", "Close the source store after dispatch"));
        problem(archived, 409, "STORE_CONFLICT");
        assertThat(jdbc.sql("SELECT active FROM stockrescue.store WHERE org_id=:org AND id=:store")
                .param("org",fixture.organization).param("store",fixture.sourceStore).query(Boolean.class).single()).isTrue();

        var receiver = fixture.destinationOperator();
        var received = receiver.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 4, 0, 2, "receipt-after-source-archive"));

        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);
        assertThat(tree(received).path("status").asString()).isEqualTo("RECEIVED");
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(2);
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("2:2:0:0:0");
    }

    @Test
    void destinationWithAnOpenTransferCannotBeArchivedAndRemainsReceivable() throws Exception {
        var owner = fixture.owner();
        var prepared = preparePickedTransfer(owner, 2);
        dispatch(owner, prepared, 3, "dispatch-before-destination-archive");
        var archived = owner.write("POST", "/api/v1/organizations/" + fixture.organization + "/stores/"
                + fixture.destinationStore + "/archive", Map.of("version", 0, "reason", "Close the destination store during transit"));
        problem(archived, 409, "STORE_CONFLICT");

        var received=owner.write("POST", base() + "/" + prepared.transferId() + "/receive",
                receiveRequest(prepared, 4, 0, 2, "receipt-to-active-destination"));
        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("2:2:0:0:0");
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(2);
    }

    @Test
    void destinationArchiveWinsBeforeTransferCreationWithoutInvertingStoreLocks() throws Exception {
        long sourceVersion=fixture.seedSource(5);
        var owner=fixture.owner();
        var archiver=fixture.owner();
        var reachedStoreRowLocks=new CountDownLatch(1);
        var continueTransfer=new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            reachedStoreRowLocks.countDown();
            if(!continueTransfer.await(15,TimeUnit.SECONDS))
                throw new IllegalStateException("Transfer store-lock barrier timed out");
            return call.callRealMethod();
        }).when(laneService).lockTransferStoresForRequest(
                org.mockito.ArgumentMatchers.eq(fixture.organization),
                org.mockito.ArgumentMatchers.eq(fixture.sourceStore),
                org.mockito.ArgumentMatchers.eq(fixture.destinationStore));

        try(var workers=Executors.newFixedThreadPool(1)) {
            var creation=workers.submit(() -> owner.write("POST",base(),createRequest(UUID.randomUUID(),
                    fixture.sourceStore,fixture.destinationStore,fixture.position,fixture.destinationLocation,
                    1,sourceVersion,"Request stock while destination archival races")));
            assertThat(reachedStoreRowLocks.await(10,TimeUnit.SECONDS)).isTrue();
            var archived=archiver.write("POST","/api/v1/organizations/"+fixture.organization+"/stores/"
                    +fixture.destinationStore+"/archive",Map.of("version",0,"reason","Close the destination store"));
            assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);
            continueTransfer.countDown();
            problem(creation.get(15,TimeUnit.SECONDS),409,"CONFIGURATION_INACTIVE");
        } finally {
            continueTransfer.countDown();
        }

        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_order WHERE org_id=:org")
                .param("org",fixture.organization).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT active FROM stockrescue.store WHERE org_id=:org AND id=:store")
                .param("org",fixture.organization).param("store",fixture.destinationStore)
                .query(Boolean.class).single()).isFalse();
        assertThat(stockOnHand(fixture.sourceStore,fixture.sourceLocation)).isEqualTo(5);
    }

    @Test
    void openTransferArchiveConflictsWhilePickingStartsWithoutInvertingStoreLocks() throws Exception {
        long sourceVersion=fixture.seedSource(5);
        var owner=fixture.owner();
        var archiver=fixture.owner();
        var created=owner.write("POST",base(),createRequest(UUID.randomUUID(),fixture.sourceStore,
                fixture.destinationStore,fixture.position,fixture.destinationLocation,1,sourceVersion,
                "Request stock before archive and pick race"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        UUID transferId=UUID.fromString(tree(created).path("transferId").asString());
        var approved=owner.write("POST",base()+"/"+transferId+"/approve",
                decision(UUID.randomUUID(),0,"Approve before the source begins picking"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);

        var reachedStoreRowLocks=new CountDownLatch(1);
        var continueTransfer=new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            reachedStoreRowLocks.countDown();
            if(!continueTransfer.await(15,TimeUnit.SECONDS))
                throw new IllegalStateException("Transfer store-lock barrier timed out");
            return call.callRealMethod();
        }).when(laneService).lockTransferStoreRows(
                org.mockito.ArgumentMatchers.eq(fixture.organization),
                org.mockito.ArgumentMatchers.eq(fixture.sourceStore),
                org.mockito.ArgumentMatchers.eq(fixture.destinationStore));

        try(var workers=Executors.newFixedThreadPool(1)) {
            var picking=workers.submit(() -> owner.write("POST",base()+"/"+transferId+"/start-pick",
                    decision(UUID.randomUUID(),1,"Begin picking while destination archival races")));
            assertThat(reachedStoreRowLocks.await(10,TimeUnit.SECONDS)).isTrue();
            var archived=archiver.write("POST","/api/v1/organizations/"+fixture.organization+"/stores/"
                    +fixture.destinationStore+"/archive",Map.of("version",0,"reason","Close the destination store"));
            problem(archived,409,"STORE_CONFLICT");
            continueTransfer.countDown();
            var outcome=picking.get(15,TimeUnit.SECONDS);
            assertThat(outcome.statusCode()).as(outcome.body()).isEqualTo(200);
            assertThat(tree(outcome).path("status").asString()).isEqualTo("PICKING");
        } finally {
            continueTransfer.countDown();
        }

        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org",fixture.organization).param("transfer",transferId).query(String.class).single())
                .isEqualTo("PICKING");
    }

    @Test
    void administratorCanSettleLegacyArchivedDestinationAndStockIsQuarantined() throws Exception {
        var owner=fixture.owner();
        var prepared=preparePickedTransfer(owner,2);
        dispatch(owner,prepared,3,"dispatch-to-legacy-archived-store");
        jdbc.sql("UPDATE stockrescue.storage_location SET active=FALSE,version=version+1,updated_at=clock_timestamp() WHERE org_id=:org AND store_id=:store AND zone_id=:zone AND active")
                .param("org",fixture.organization).param("store",fixture.destinationStore).param("zone",fixture.destinationZone).update();
        jdbc.sql("UPDATE stockrescue.storage_zone SET active=FALSE,version=version+1,updated_at=clock_timestamp() WHERE org_id=:org AND store_id=:store AND id=:zone")
                .param("org",fixture.organization).param("store",fixture.destinationStore).param("zone",fixture.destinationZone).update();
        jdbc.sql("UPDATE stockrescue.product SET active=FALSE,version=version+1 WHERE org_id=:org AND id=:product")
                .param("org",fixture.organization).param("product",fixture.product).update();
        jdbc.sql("UPDATE stockrescue.store SET active=FALSE,version=version+1,updated_at=clock_timestamp() WHERE org_id=:org AND id=:store")
                .param("org",fixture.organization).param("store",fixture.destinationStore).update();

        problem(fixture.destinationOperator().write("POST",base()+"/"+prepared.transferId()+"/receive",
                receiveRequest(prepared,4,0,2,"unauthorized-archived-store-receipt")),404,"RESOURCE_NOT_FOUND");
        problem(fixture.destinationOperator().get(base()+"/"+prepared.transferId()+"/settlement-positions"),404,"RESOURCE_NOT_FOUND");
        var detail=owner.get(base()+"/"+prepared.transferId());
        assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
        var capabilities=tree(owner.get(base()+"/"+prepared.transferId()+"/capabilities"));
        assertThat(capabilities.path("destinationActions").isEmpty()).isTrue();
        assertThat(capabilities.path("canReceivePhysicalSettlement").asBoolean()).isTrue();
        assertThat(capabilities.path("canConfirmPhysicalReturn").asBoolean()).isFalse();
        var positionVersions=tree(owner.get(base()+"/"+prepared.transferId()+"/settlement-positions"));
        assertThat(positionVersions.path("destinationPositions").get(0).path("version").asLong()).isZero();
        var receipt=receiveRequest(prepared,4,0,2,"admin-legacy-archived-store-receipt");
        var preparedIntent=owner.write("POST","/api/v1/organizations/"+fixture.organization+"/transfer-command-intents",Map.of(
                "prepareId",UUID.randomUUID(),"operation","RECEIVE","transferId",prepared.transferId(),"payload",receipt));
        assertThat(preparedIntent.statusCode()).as(preparedIntent.body()).isEqualTo(200);
        UUID intentId=UUID.fromString(tree(preparedIntent).path("id").asString());
        var submitted=owner.write("POST","/api/v1/organizations/"+fixture.organization+"/transfer-command-intents/"+intentId+"/submitted",null);
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(200);
        var received=owner.write("POST",base()+"/"+prepared.transferId()+"/receive",receipt);

        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);
        var inspection=tree(owner.get("/api/v1/organizations/"+fixture.organization+"/transfer-command-intents/"+intentId));
        assertThat(inspection.path("executionStatus").asString()).isEqualTo("COMMITTED_AWAITING_ACKNOWLEDGEMENT");
        assertThat(inspection.path("committedOutcome").path("status").asString()).isEqualTo("RECEIVED");
        assertThat(owner.write("POST","/api/v1/organizations/"+fixture.organization+"/transfer-command-intents/"+intentId+"/acknowledge",null)
                .statusCode()).isEqualTo(200);
        assertThat(custody(prepared.transferId(),prepared.lineId())).isEqualTo("2:2:0:0:0");
        assertThat(jdbc.sql("SELECT on_hand || ':' || quarantined || ':' || available FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND location_id=:location AND lot_id=:lot")
                .param("org",fixture.organization).param("store",fixture.destinationStore).param("location",fixture.destinationLocation)
                .param("lot",fixture.lot).query(String.class).single()).isEqualTo("2:2:0");
    }

    @Test
    void productAndLocationsCannotBeArchivedWhileTheyAnchorOpenTransferCustody() throws Exception {
        var owner=fixture.owner();
        var prepared=preparePickedTransfer(owner,2);
        dispatch(owner,prepared,3,"dispatch-before-context-archive");

        long productVersion=jdbc.sql("SELECT version FROM stockrescue.product WHERE org_id=:org AND id=:product")
                .param("org",fixture.organization).param("product",fixture.product).query(Long.class).single();
        problem(owner.write("POST","/api/v1/organizations/"+fixture.organization+"/products/"+fixture.product+"/archive",
                Map.of("requestId",UUID.randomUUID(),"expectedVersion",productVersion,"reason","Retire product with goods still in transit")),
                409,"CATALOG_CONFLICT");
        for(var location:java.util.List.of(fixture.sourceLocation,fixture.destinationLocation)) {
            UUID store=location.equals(fixture.sourceLocation)?fixture.sourceStore:fixture.destinationStore;
            problem(owner.write("POST","/api/v1/organizations/"+fixture.organization+"/stores/"+store+
                    "/storage-locations/"+location+"/archive",Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,
                            "reason","Keep the physical transfer context usable until custody settles")),409,"CONFIGURATION_CONFLICT");
        }
        assertThat(jdbc.sql("SELECT active FROM stockrescue.product WHERE org_id=:org AND id=:product")
                .param("org",fixture.organization).param("product",fixture.product).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.storage_location WHERE org_id=:org AND id IN (:source,:destination) AND active")
                .param("org",fixture.organization).param("source",fixture.sourceLocation).param("destination",fixture.destinationLocation)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(custody(prepared.transferId(),prepared.lineId())).isEqualTo("2:0:0:0:2");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document WHERE org_id=:org AND transfer_id=:transfer AND document_type='DISPATCH'")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void administratorCanPrepareAndConfirmReturnAtLegacyArchivedSource() throws Exception {
        var owner=fixture.owner();
        var sourceOperator=fixture.operator();
        var prepared=preparePickedTransfer(owner,2);
        dispatch(owner,prepared,3,"dispatch-before-legacy-source-return");
        var returnReference="legacy-source-return-"+UUID.randomUUID();
        var requested=owner.write("POST",base()+"/"+prepared.transferId()+"/request-return",Map.of(
                "requestId",UUID.randomUUID(),"expectedVersion",4,"requestReference",returnReference,
                "lines",java.util.List.of(Map.of("lineId",prepared.lineId())),"reason","Carrier returned the final packages",
                "evidenceReference","carrier:legacy-source-return"));
        assertThat(requested.statusCode()).as(requested.body()).isEqualTo(200);
        jdbc.sql("UPDATE stockrescue.store SET active=FALSE,version=version+1,updated_at=clock_timestamp() WHERE org_id=:org AND id=:store")
                .param("org",fixture.organization).param("store",fixture.sourceStore).update();

        var capabilities=tree(owner.get(base()+"/"+prepared.transferId()+"/capabilities"));
        assertThat(capabilities.path("sourceActions").isEmpty()).isTrue();
        assertThat(capabilities.path("canConfirmPhysicalReturn").asBoolean()).isTrue();
        assertThat(capabilities.path("canReceivePhysicalSettlement").asBoolean()).isFalse();
        var versions=tree(owner.get(base()+"/"+prepared.transferId()+"/settlement-positions"));
        long sourceVersion=versions.path("sourcePositions").get(0).path("version").asLong();
        assertThat(sourceVersion).isEqualTo(stockVersion(fixture.sourceStore,fixture.position));
        var storedReference=jdbc.sql("SELECT observed_state->>'requestReference' FROM stockrescue.transfer_reconciliation_evidence WHERE org_id=:org AND command_id=:command AND evidence_type='RETURN_REQUEST'")
                .param("org",fixture.organization).param("command",UUID.fromString(tree(requested).path("commandId").asString()))
                .query(String.class).single();
        var confirmation=confirmReturnRequest(UUID.randomUUID(),5,storedReference,"legacy-return-receipt-"+UUID.randomUUID(),
                prepared.lineId(),sourceVersion,"legacy-return-line",prepared.sku(),prepared.lotCode());
        problem(sourceOperator.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",confirmation),404,"RESOURCE_NOT_FOUND");
        var preparedIntent=owner.write("POST","/api/v1/organizations/"+fixture.organization+"/transfer-command-intents",Map.of(
                "prepareId",UUID.randomUUID(),"operation","CONFIRM_RETURN","transferId",prepared.transferId(),"payload",confirmation));
        assertThat(preparedIntent.statusCode()).as(preparedIntent.body()).isEqualTo(200);
        UUID intentId=UUID.fromString(tree(preparedIntent).path("id").asString());
        assertThat(owner.write("POST","/api/v1/organizations/"+fixture.organization+"/transfer-command-intents/"+intentId+"/submitted",null)
                .statusCode()).isEqualTo(200);
        var received=owner.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",confirmation);
        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);
        var inspection=tree(owner.get("/api/v1/organizations/"+fixture.organization+"/transfer-command-intents/"+intentId));
        assertThat(inspection.path("executionStatus").asString()).isEqualTo("COMMITTED_AWAITING_ACKNOWLEDGEMENT");
        assertThat(owner.write("POST","/api/v1/organizations/"+fixture.organization+"/transfer-command-intents/"+intentId+"/acknowledge",null)
                .statusCode()).isEqualTo(200);
        assertThat(custody(prepared.transferId(),prepared.lineId())).isEqualTo("2:0:0:2:0");
        assertThat(jdbc.sql("SELECT on_hand || ':' || quarantined || ':' || available FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND id=:position")
                .param("org",fixture.organization).param("store",fixture.sourceStore).param("position",fixture.position)
                .query(String.class).single()).isEqualTo("5:2:3");
    }

    @Test
    void archivedLotCanBePhysicallyReceivedAndReturnedOnlyIntoQuarantine() throws Exception {
        var owner=fixture.owner();
        var prepared=preparePickedTransfer(owner,20);
        dispatch(owner,prepared,3,"dispatch-before-lot-archive");
        var archived=owner.write("POST","/api/v1/organizations/"+fixture.organization+"/lots/"+fixture.lot+"/archive",
                Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Recall lot metadata after physical dispatch"));
        assertThat(archived.statusCode()).as(archived.body()).isEqualTo(201);
        String[] sourceBefore=jdbc.sql("SELECT on_hand || ':' || quarantined || ':' || available FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND location_id=:location AND lot_id=:lot")
                .param("org",fixture.organization).param("store",fixture.sourceStore).param("location",fixture.sourceLocation)
                .param("lot",fixture.lot).query(String.class).single().split(":");

        var received=owner.write("POST",base()+"/"+prepared.transferId()+"/receive",
                receiveRequest(prepared,4,0,18,"receipt-after-lot-archive"));
        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT on_hand || ':' || quarantined || ':' || available FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND location_id=:location AND lot_id=:lot")
                .param("org",fixture.organization).param("store",fixture.destinationStore).param("location",fixture.destinationLocation)
                .param("lot",fixture.lot).query(String.class).single()).isEqualTo("18:18:0");

        var request=owner.write("POST",base()+"/"+prepared.transferId()+"/request-return",Map.of(
                "requestId",UUID.randomUUID(),"expectedVersion",5,"requestReference","archived-lot-return-"+UUID.randomUUID(),
                "lines",java.util.List.of(Map.of("lineId",prepared.lineId())),
                "reason","Return the two packages left with the carrier","evidenceReference","carrier:archived-lot-return"));
        assertThat(request.statusCode()).as(request.body()).isEqualTo(200);
        var storedReference=jdbc.sql("SELECT observed_state->>'requestReference' FROM stockrescue.transfer_reconciliation_evidence WHERE org_id=:org AND command_id=:command AND evidence_type='RETURN_REQUEST'")
                .param("org",fixture.organization).param("command",UUID.fromString(tree(request).path("commandId").asString()))
                .query(String.class).single();
        var returned=owner.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",
                confirmReturnRequest(UUID.randomUUID(),6,storedReference,"return-after-lot-archive-"+UUID.randomUUID(),
                        prepared.lineId(),stockVersion(fixture.sourceStore,fixture.position),"archived-lot-return-line",
                        prepared.sku(),prepared.lotCode()));
        assertThat(returned.statusCode()).as(returned.body()).isEqualTo(200);
        assertThat(custody(prepared.transferId(),prepared.lineId())).isEqualTo("20:18:0:2:0");
        assertThat(jdbc.sql("SELECT on_hand || ':' || quarantined || ':' || available FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND location_id=:location AND lot_id=:lot")
                .param("org",fixture.organization).param("store",fixture.sourceStore).param("location",fixture.sourceLocation)
                .param("lot",fixture.lot).query(String.class).single()).isEqualTo((Long.parseLong(sourceBefore[0])+2)+":"+
                        (Long.parseLong(sourceBefore[1])+2)+":"+sourceBefore[2]);
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
    void custodyTimelineReturnsEveryPhysicalReceiptAcrossStablePagesBeyondOneHundredEntries() throws Exception {
        var browser = fixture.owner();
        var prepared = preparePickedTransfer(browser, 101);
        dispatch(browser, prepared, 3, "dispatch-custody-pagination");

        for (int index = 0; index < 101; index++) {
            var response = browser.write("POST", base() + "/" + prepared.transferId() + "/receive",
                    receiveRequest(prepared, 4 + index, index, 1, "receipt-custody-page-" + index));
            assertThat(response.statusCode()).as("receipt %s: %s", index, response.body()).isEqualTo(200);
        }

        var firstResponse = browser.get(base() + "/" + prepared.transferId() + "/custody?limit=50");
        assertThat(firstResponse.statusCode()).as(firstResponse.body()).isEqualTo(200);
        var first = tree(firstResponse);
        assertThat(first.path("nextCursor").asString()).isNotBlank();
        var secondResponse = browser.get(base() + "/" + prepared.transferId() + "/custody?limit=50&cursor=" + first.path("nextCursor").asString());
        assertThat(secondResponse.statusCode()).as(secondResponse.body()).isEqualTo(200);
        var second = tree(secondResponse);
        assertThat(second.path("nextCursor").asString()).isNotBlank();
        var thirdResponse = browser.get(base() + "/" + prepared.transferId() + "/custody?limit=50&cursor=" + second.path("nextCursor").asString());
        assertThat(thirdResponse.statusCode()).as(thirdResponse.body()).isEqualTo(200);
        var third = tree(thirdResponse);
        assertThat(first.path("items")).hasSize(50);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(second.path("items")).hasSize(50);
        assertThat(second.path("hasMore").asBoolean()).isTrue();
        assertThat(third.path("items")).hasSize(2);
        assertThat(third.path("hasMore").asBoolean()).isFalse();
        var movementIds = java.util.stream.Stream.of(first, second, third)
                .flatMap(page -> java.util.stream.StreamSupport.stream(page.path("items").spliterator(), false))
                .map(item -> item.path("id").asString()).toList();
        assertThat(movementIds).hasSize(102).doesNotHaveDuplicates();
        assertThat(java.util.stream.Stream.of(first, second, third)
                .flatMap(page -> java.util.stream.StreamSupport.stream(page.path("items").spliterator(), false))
                .filter(item -> item.path("type").asString().equals("RECEIPT")).count()).isEqualTo(101);
    }

    @Test
    void transferEvidenceCursorReachesMoreThanTenThousandRowsAndRejectsCrossQueryReuse() throws Exception {
        var browser = fixture.owner();
        var prepared = preparePickedTransfer(browser, 2);
        dispatch(browser, prepared, 3, "dispatch-evidence-cursor-pagination");
        var opened = browser.write("POST", base() + "/" + prepared.transferId() + "/open-discrepancy",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 4, "lineId", prepared.lineId(),
                        "expectedState", "Two units remain in transit", "observedState", "Carrier has not confirmed delivery",
                        "reason", "Create a large deterministic evidence history for cursor pagination",
                        "evidenceReference", "cursor-pagination:initial"));
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);
        UUID commandId = UUID.fromString(tree(opened).path("commandId").asString());
        UUID actorId = jdbc.sql("SELECT actor_user_id FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND id=:command")
                .param("org", fixture.organization).param("command", commandId).query(UUID.class).single();
        UUID membershipId = jdbc.sql("SELECT membership_id FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND id=:command")
                .param("org", fixture.organization).param("command", commandId).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO stockrescue.transfer_reconciliation_evidence
                    (id,org_id,transfer_id,line_id,command_id,actor_user_id,membership_id,evidence_type,
                     expected_state,observed_state,reason,evidence_reference)
                SELECT gen_random_uuid(),:org,:transfer,:line,:command,:actor,:membership,'DISCREPANCY',
                       jsonb_build_object('sequence',entry),jsonb_build_object('observedSequence',entry),
                       'Cursor pagination fixture evidence','cursor-pagination:' || entry
                FROM generate_series(1,10050) AS series(entry)
                """)
                .param("org", fixture.organization).param("transfer", prepared.transferId()).param("line", prepared.lineId())
                .param("command", commandId).param("actor", actorId).param("membership", membershipId).update();

        String scopedPath = base() + "/" + prepared.transferId() + "/discrepancies?limit=100";
        var pageResponse = browser.get(scopedPath);
        assertThat(pageResponse.statusCode()).as(pageResponse.body()).isEqualTo(200);
        var page = tree(pageResponse);
        var evidenceIds = new java.util.HashSet<String>();
        int pageCount = 0;
        do {
            pageCount++;
            for (JsonNode item : page.path("items")) evidenceIds.add(item.path("id").asString());
            if (!page.path("hasMore").asBoolean()) break;
            String cursor = page.path("nextCursor").asString();
            assertThat(cursor).isNotBlank();
            pageResponse = browser.get(scopedPath + "&cursor=" + cursor);
            assertThat(pageResponse.statusCode()).as(pageResponse.body()).isEqualTo(200);
            page = tree(pageResponse);
        } while (pageCount < 110);
        assertThat(pageCount).isEqualTo(101);
        assertThat(evidenceIds).hasSize(10051);
        var reused = browser.get(base() + "/discrepancies?storeId=" + fixture.destinationStore + "&limit=100&cursor="
                + tree(browser.get(scopedPath)).path("nextCursor").asString());
        assertThat(reused.statusCode()).isEqualTo(400);
        assertThat(tree(reused).path("code").asString()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void returnRequestLeavesCustodyOutstandingUntilTheSourceConfirmsPhysicalReturn() throws Exception {
        var manager = fixture.storeManager();
        var prepared = preparePickedTransfer(manager, 5);
        dispatch(manager, prepared, 3, "dispatch-return-proof");
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("5:0:0:0:5");
        assertThat(stockOnHand(fixture.sourceStore, fixture.sourceLocation)).isEqualTo(3);

        var returnReference = "return-request-" + UUID.randomUUID();
        var returnRequestId = UUID.randomUUID();
        var requested = manager.write("POST", base() + "/" + prepared.transferId() + "/request-return",
                Map.of("requestId", returnRequestId, "expectedVersion", 4, "requestReference", returnReference,
                        "lines", java.util.List.of(Map.of("lineId", prepared.lineId())),
                        "reason", "Return remaining goods to source", "evidenceReference", "carrier:return-request"));
        assertThat(requested.statusCode()).as(requested.body()).isEqualTo(200);
        assertThat(tree(requested).path("status").asString()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("5:0:0:0:5");
        assertThat(stockOnHand(fixture.sourceStore, fixture.sourceLocation)).isEqualTo(3);

        var activeReturns = tree(manager.get(base() + "/" + prepared.transferId() + "/active-returns"));
        assertThat(activeReturns.path("items")).hasSize(1);
        assertThat(activeReturns.path("items").get(0).path("requestReference").asString()).isEqualTo(returnReference);
        assertThat(activeReturns.path("items").get(0).path("requestId").asString()).isEqualTo(returnRequestId.toString());
        assertThat(activeReturns.path("items").get(0).path("requestedQuantity").asLong()).isEqualTo(5);
        assertThat(activeReturns.path("items").get(0).path("unsettledQuantity").asLong()).isEqualTo(5);
        var foreign = fixture.separateOrganizationAdmin();
        var crossTenant = foreign.get("/api/v1/organizations/" + fixture.foreignOrganization + "/transfers/"
                + prepared.transferId() + "/active-returns");
        assertThat(crossTenant.statusCode()).isEqualTo(404);
        assertThat(crossTenant.body()).doesNotContain(returnReference);

        UUID requestCommand = UUID.fromString(tree(requested).path("commandId").asString());
        UUID evidenceActor = jdbc.sql("SELECT actor_user_id FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND id=:command")
                .param("org", fixture.organization).param("command", requestCommand).query(UUID.class).single();
        UUID evidenceMembership = jdbc.sql("SELECT membership_id FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND id=:command")
                .param("org", fixture.organization).param("command", requestCommand).query(UUID.class).single();
        for (int index = 0; index < 101; index++) {
            jdbc.sql("""
                    INSERT INTO stockrescue.transfer_reconciliation_evidence
                        (id,org_id,transfer_id,line_id,command_id,actor_user_id,membership_id,evidence_type,
                         expected_state,observed_state,reason,evidence_reference)
                    VALUES (:id,:org,:transfer,:line,:command,:actor,:membership,'DISCREPANCY',
                            '{\"outstanding\":5}',CAST(:observed AS jsonb),'Pagination fixture evidence',:reference)
                    """)
                    .param("id", UUID.randomUUID()).param("org", fixture.organization).param("transfer", prepared.transferId())
                    .param("line", prepared.lineId()).param("command", requestCommand).param("actor", evidenceActor)
                    .param("membership", evidenceMembership).param("observed", "{\"sample\":" + index + "}")
                    .param("reference", "pagination-evidence-" + index).update();
        }

        var inTransit = tree(manager.get(base() + "/in-transit?storeId=" + fixture.sourceStore));
        assertThat(inTransit.path("items")).hasSize(1);
        assertThat(inTransit.path("items").get(0).path("outstanding").asLong()).isEqualTo(5);
        var discrepancies = tree(manager.get(base() + "/discrepancies?storeId=" + fixture.destinationStore + "&limit=50"));
        assertThat(discrepancies.path("items")).hasSize(50);
        assertThat(discrepancies.path("hasMore").asBoolean()).isTrue();
        assertThat(textValues(discrepancies.path("items"), "evidenceReference"))
                .doesNotContain("carrier:return-request");
        var nextDiscrepancies = tree(manager.get(base() + "/discrepancies?storeId=" + fixture.destinationStore
                + "&offset=100&limit=50"));
        assertThat(nextDiscrepancies.path("items")).hasSize(2);
        assertThat(nextDiscrepancies.path("hasMore").asBoolean()).isFalse();
        assertThat(textValues(nextDiscrepancies.path("items"), "id"))
                .doesNotContainAnyElementsOf(textValues(discrepancies.path("items"), "id"));
        assertThat(textValues(nextDiscrepancies.path("items"), "evidenceReference"))
                .contains("carrier:return-request");
        assertThat(tree(manager.get(base() + "/" + prepared.transferId() + "/active-returns")).path("items"))
                .hasSize(1);

        var sourceVersion = stockVersion(fixture.sourceStore, fixture.position);
        var physicalReceiptReference = "return-receipt-" + UUID.randomUUID();
        var returned = manager.write("POST", base() + "/" + prepared.transferId() + "/confirm-return",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 5, "requestReference", returnReference,
                        "documentReference", physicalReceiptReference,
                        "lines", java.util.List.of(Map.of("lineId", prepared.lineId(), "quantity", 5, "expectedSourceVersion", sourceVersion,
                                "documentLineReference", "returned-line-1", "observedSku", prepared.sku(),
                                "observedLotCode", prepared.lotCode())),
                        "reason", "Counted the returned goods at source", "evidenceReference", "source:return-receipt"));
        assertThat(returned.statusCode()).as(returned.body()).isEqualTo(200);
        assertThat(tree(returned).path("status").asString()).isEqualTo("RECONCILIATION_REQUIRED");
        assertTransferEvent(prepared.transferId(), 5, "TransferCommandRecorded", "REQUEST_RETURN", "RECONCILIATION_REQUIRED");
        assertTransferEvent(prepared.transferId(), 6, "TransferReturned", "CONFIRM_RETURN", "RECONCILIATION_REQUIRED");
        assertThat(tree(returned).path("stockMovementId").asString()).isNotBlank();
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("5:0:0:5:0");
        assertThat(stockOnHand(fixture.sourceStore, fixture.sourceLocation)).isEqualTo(8);
        assertThat(tree(manager.get(base() + "/" + prepared.transferId() + "/active-returns")).path("items").size()).isZero();
        assertThat(jdbc.sql("SELECT operation FROM stockrescue.stock_movement WHERE org_id=:org AND id=(SELECT stock_movement_id FROM stockrescue.transfer_custody_movement WHERE org_id=:org AND transfer_id=:transfer AND line_id=:line AND movement_type='RETURN')")
                .param("org", fixture.organization).param("transfer", prepared.transferId()).param("line", prepared.lineId())
                .query(String.class).single()).isEqualTo("TRANSFER_RETURN");

        var closed = manager.write("POST", base() + "/" + prepared.transferId() + "/close-discrepancy",
                decision(UUID.randomUUID(), 6, "Close the documented physical return"));
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        assertThat(tree(closed).path("status").asString()).isEqualTo("CLOSED_WITH_DISCREPANCY");
        assertTransferEvent(prepared.transferId(), 7, "TransferDiscrepancyResolved", "CLOSE", "CLOSED_WITH_DISCREPANCY");
        var reconciliationPage = tree(manager.get(base() + "/discrepancies?storeId=" + fixture.destinationStore + "&limit=50"));
        JsonNode reconciliation = null;
        for (JsonNode item : reconciliationPage.path("items")) {
            if (item.path("type").asString().equals("CUSTODY_RECONCILIATION")) {
                reconciliation = item;
                break;
            }
        }
        assertThat(reconciliation).isNotNull();
        assertThat(reconciliation.path("actorId").asString()).isNotBlank();
        assertThat(reconciliation.path("recordedAt").asString()).isNotBlank();
        assertThat(reconciliation.path("expectedState").asString()).isNotBlank();
        assertThat(reconciliation.path("observedState").asString()).isNotBlank();
        assertThat(reconciliation.path("evidenceReference").asString()).isNotBlank();
        assertThat(reconciliation.path("lineId").isNull()).isTrue();
        assertThat(reconciliation.path("resolutionScope").asString()).isEqualTo("TRANSFER_CLOSED");
        assertThat(reconciliation.path("lineSettled").asBoolean()).isFalse();
        assertThat(reconciliation.path("transferClosed").asBoolean()).isTrue();
    }

    @Test
    void oneLinePhysicalReturnCanBeSettledInSeparateFourAndSixUnitDocuments() throws Exception {
        var manager=fixture.storeManager();
        var prepared=preparePickedTransfer(manager,10);
        dispatch(manager,prepared,3,"dispatch-single-line-staged-return");
        String requestReference="single-line-return-"+UUID.randomUUID();
        var request=manager.write("POST",base()+"/"+prepared.transferId()+"/request-return",Map.of(
                "requestId",UUID.randomUUID(),"expectedVersion",4,"requestReference",requestReference,
                "lines",java.util.List.of(Map.of("lineId",prepared.lineId())),
                "reason","Request a staged return for one transfer line","evidenceReference","carrier:staged-single-line"));
        assertThat(request.statusCode()).as(request.body()).isEqualTo(200);

        UUID firstRequestId=UUID.randomUUID();
        var firstCommand=confirmReturnRequest(firstRequestId,5,requestReference,"single-line-return-R-01",
                prepared.lineId(),4,stockVersion(fixture.sourceStore,fixture.position),"return-line-R-01",prepared.sku(),prepared.lotCode());
        var first=manager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",firstCommand);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(custody(prepared.transferId(),prepared.lineId())).isEqualTo("10:0:0:4:6");
        var partial=tree(manager.get(base()+"/"+prepared.transferId()+"/active-returns")).path("items").get(0);
        assertThat(partial.path("requestedQuantity").asLong()).isEqualTo(10);
        assertThat(partial.path("settledQuantity").asLong()).isEqualTo(4);
        assertThat(partial.path("unsettledQuantity").asLong()).isEqualTo(6);
        assertThat(partial.path("state").asString()).isEqualTo("PARTIALLY_FULFILLED");

        problem(manager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",
                confirmReturnRequest(UUID.randomUUID(),6,requestReference,"single-line-return-overage",
                        prepared.lineId(),7,stockVersion(fixture.sourceStore,fixture.position),"return-line-overage",
                        prepared.sku(),prepared.lotCode())),409,"INVENTORY_CONFLICT");
        var second=manager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",
                confirmReturnRequest(UUID.randomUUID(),6,requestReference,"single-line-return-R-02",
                        prepared.lineId(),6,stockVersion(fixture.sourceStore,fixture.position),"return-line-R-02",
                        prepared.sku(),prepared.lotCode()));
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(custody(prepared.transferId(),prepared.lineId())).isEqualTo("10:0:0:10:0");
        assertThat(stockOnHand(fixture.sourceStore,fixture.sourceLocation)).isEqualTo(13);
        assertThat(tree(manager.get(base()+"/"+prepared.transferId()+"/active-returns")).path("items")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document_line WHERE org_id=:org AND transfer_id=:transfer AND document_type='RETURN'")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single()).isEqualTo(2);

        var replay=manager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",firstCommand);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(custody(prepared.transferId(),prepared.lineId())).isEqualTo("10:0:0:10:0");
        var duplicateDocument=manager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",
                confirmReturnRequest(UUID.randomUUID(),7,requestReference,"single-line-return-R-01",
                        prepared.lineId(),1,stockVersion(fixture.sourceStore,fixture.position),"return-line-duplicate",
                        prepared.sku(),prepared.lotCode()));
        assertThat(duplicateDocument.statusCode()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT observed_state->>'state' FROM stockrescue.transfer_reconciliation_evidence WHERE org_id=:org AND transfer_id=:transfer AND evidence_type='RETURN_FULFILLED' ORDER BY recorded_at DESC LIMIT 1")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).query(String.class).single()).isEqualTo("FULFILLED");
    }

    @Test
    void oneMultiLineReturnRequestCanBePhysicallyConfirmedInStages() throws Exception {
        var firstManager=fixture.storeManager();
        var secondManager=fixture.storeManager();
        var prepared=prepareSharedDestinationTransfer(firstManager,2,5);
        UUID firstLine=UUID.fromString(prepared.firstLine().path("id").asString());
        UUID secondLine=UUID.fromString(prepared.secondLine().path("id").asString());
        String requestReference="return-request-staged-"+UUID.randomUUID();
        var requestId=UUID.randomUUID();
        var requested=firstManager.write("POST",base()+"/"+prepared.transferId()+"/request-return",Map.of(
                "requestId",requestId,"expectedVersion",5,"requestReference",requestReference,
                "lines",java.util.List.of(Map.of("lineId",firstLine),Map.of("lineId",secondLine)),
                "reason","Return both outstanding lines in separate physical receipts",
                "evidenceReference","carrier:staged-return-request"));
        assertThat(requested.statusCode()).as(requested.body()).isEqualTo(200);
        assertThat(transfers.pendingReturn(fixture.organization,prepared.transferId(),firstLine).orElseThrow())
                .satisfies(pending -> { assertThat(pending.quantity()).isEqualTo(2);assertThat(pending.requestReference()).isEqualTo(requestReference); });
        assertThat(transfers.pendingReturn(fixture.organization,prepared.transferId(),secondLine).orElseThrow())
                .satisfies(pending -> { assertThat(pending.quantity()).isEqualTo(5);assertThat(pending.requestReference()).isEqualTo(requestReference); });
        assertThat(stockOnHand(fixture.sourceStore,fixture.sourceLocation)).isZero();
        assertThat(stockOnHand(fixture.sourceStore,fixture.secondSourceLocation)).isZero();

        long firstSourceVersion=stockVersion(fixture.sourceStore,fixture.position);
        long secondSourceVersion=stockVersion(fixture.sourceStore,fixture.secondPosition);
        var wrongRequest=firstManager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",
                confirmReturnRequest(UUID.randomUUID(),6,"wrong-request-reference","wrong-request-receipt",firstLine,
                        firstSourceVersion,"wrong-request-line",prepared.firstLine().path("sku").asString(),
                        prepared.firstLine().path("lotCode").asString()));
        assertThat(wrongRequest.statusCode()).isEqualTo(409);
        var wrongSku=firstManager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",
                confirmReturnRequest(UUID.randomUUID(),6,requestReference,"wrong-sku-receipt",firstLine,
                        firstSourceVersion,"wrong-sku-line","OTHER-SKU",prepared.firstLine().path("lotCode").asString()));
        assertThat(wrongSku.statusCode()).isEqualTo(409);
        var wrongLot=firstManager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",
                confirmReturnRequest(UUID.randomUUID(),6,requestReference,"wrong-lot-receipt",firstLine,
                        firstSourceVersion,"wrong-lot-line",prepared.firstLine().path("sku").asString(),"OTHER-LOT"));
        assertThat(wrongLot.statusCode()).isEqualTo(409);
        var staleSourceVersion=firstManager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",
                confirmReturnRequest(UUID.randomUUID(),6,requestReference,"stale-version-receipt",firstLine,
                        firstSourceVersion+1,"stale-version-line",prepared.firstLine().path("sku").asString(),
                        prepared.firstLine().path("lotCode").asString()));
        assertThat(staleSourceVersion.statusCode()).isEqualTo(409);
        var foreign=fixture.separateOrganizationAdmin().write("POST",base()+"/"+prepared.transferId()+"/confirm-return",
                confirmReturnRequest(UUID.randomUUID(),6,requestReference,"foreign-receipt",firstLine,
                        firstSourceVersion,"foreign-line",prepared.firstLine().path("sku").asString(),
                        prepared.firstLine().path("lotCode").asString()));
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(stockOnHand(fixture.sourceStore,fixture.sourceLocation)).isZero();
        assertThat(stockOnHand(fixture.sourceStore,fixture.secondSourceLocation)).isZero();
        assertThat(custody(prepared.transferId(),firstLine)).isEqualTo("2:0:0:0:2");
        assertThat(custody(prepared.transferId(),secondLine)).isEqualTo("5:0:0:0:5");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_custody_movement WHERE org_id=:org AND transfer_id=:transfer AND movement_type='RETURN'")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single()).isZero();

        String firstReceiptReference="staged-receipt-A-"+UUID.randomUUID();
        String competingReceiptReference="staged-receipt-A-race-"+UUID.randomUUID();
        var firstCommand=confirmReturnRequest(UUID.randomUUID(),6,requestReference,firstReceiptReference,firstLine,
                firstSourceVersion,"returned-line-A",prepared.firstLine().path("sku").asString(),prepared.firstLine().path("lotCode").asString());
        var competingCommand=confirmReturnRequest(UUID.randomUUID(),6,requestReference,competingReceiptReference,firstLine,
                firstSourceVersion,"returned-line-A-race",prepared.firstLine().path("sku").asString(),prepared.firstLine().path("lotCode").asString());
        var race=concurrent(
                () -> firstManager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",firstCommand),
                () -> secondManager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",competingCommand));
        assertThat(race.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200,409);
        int winnerIndex=race.get(0).statusCode()==200?0:1;
        var winningCommand=winnerIndex==0?firstCommand:competingCommand;
        var winningManager=winnerIndex==0?firstManager:secondManager;
        String winningReceiptReference=(String)winningCommand.get("documentReference");
        var replay=winningManager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",winningCommand);
        assertThat(tree(replay)).isEqualTo(tree(race.get(winnerIndex)));
        var alteredRetry=new java.util.HashMap<String,Object>(winningCommand);
        alteredRetry.put("reason","Changed physical return after commit");
        assertThat(winningManager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",alteredRetry).statusCode()).isEqualTo(409);
        assertThat(stockOnHand(fixture.sourceStore,fixture.sourceLocation)).isEqualTo(2);
        assertThat(stockOnHand(fixture.sourceStore,fixture.secondSourceLocation)).isZero();
        assertThat(custody(prepared.transferId(),firstLine)).isEqualTo("2:0:0:2:0");
        assertThat(custody(prepared.transferId(),secondLine)).isEqualTo("5:0:0:0:5");
        assertThat(transfers.pendingReturn(fixture.organization,prepared.transferId(),firstLine)).isEmpty();
        assertThat(transfers.pendingReturn(fixture.organization,prepared.transferId(),secondLine).orElseThrow().requestReference())
                .isEqualTo(requestReference);

        var reusedReceipt=firstManager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",
                confirmReturnRequest(UUID.randomUUID(),7,requestReference,winningReceiptReference,secondLine,
                        secondSourceVersion,"returned-line-B-reused",prepared.secondLine().path("sku").asString(),
                        prepared.secondLine().path("lotCode").asString()));
        assertThat(reusedReceipt.statusCode()).isEqualTo(409);
        assertThat(stockOnHand(fixture.sourceStore,fixture.secondSourceLocation)).isZero();
        assertThat(custody(prepared.transferId(),secondLine)).isEqualTo("5:0:0:0:5");

        String secondReceiptReference="staged-receipt-B-"+UUID.randomUUID();
        var secondReceipt=firstManager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",
                confirmReturnRequest(UUID.randomUUID(),7,requestReference,secondReceiptReference,secondLine,
                        secondSourceVersion,"returned-line-B",prepared.secondLine().path("sku").asString(),
                        prepared.secondLine().path("lotCode").asString()));
        assertThat(secondReceipt.statusCode()).as(secondReceipt.body()).isEqualTo(200);
        assertThat(stockOnHand(fixture.sourceStore,fixture.sourceLocation)).isEqualTo(2);
        assertThat(stockOnHand(fixture.sourceStore,fixture.secondSourceLocation)).isEqualTo(5);
        assertThat(custody(prepared.transferId(),firstLine)).isEqualTo("2:0:0:2:0");
        assertThat(custody(prepared.transferId(),secondLine)).isEqualTo("5:0:0:5:0");
        assertThat(transfers.pendingReturn(fixture.organization,prepared.transferId(),firstLine)).isEmpty();
        assertThat(transfers.pendingReturn(fixture.organization,prepared.transferId(),secondLine)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document WHERE org_id=:org AND transfer_id=:transfer AND document_type='RETURN'")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document_line l JOIN stockrescue.transfer_reconciliation_evidence e ON e.org_id=l.org_id AND e.transfer_id=l.transfer_id AND e.line_id=l.line_id AND e.id=l.return_request_evidence_id WHERE l.org_id=:org AND l.transfer_id=:transfer AND l.document_type='RETURN' AND e.command_id=(SELECT id FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND transfer_id=:transfer AND operation='REQUEST_RETURN' AND request_id=:request)")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).param("request",requestId)
                .query(Long.class).single()).isEqualTo(2);
        var closed=firstManager.write("POST",base()+"/"+prepared.transferId()+"/close-discrepancy",
                decision(UUID.randomUUID(),8,"Close both evidenced physical return receipts"));
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        assertThat(tree(closed).path("status").asString()).isEqualTo("CLOSED_WITH_DISCREPANCY");
    }

    @Test
    void unsafePhysicalReturnIsQuarantinedAtTheSource() throws Exception {
        var owner=fixture.owner();
        var prepared=preparePickedTransfer(owner,2);
        dispatch(owner,prepared,3,"dispatch-unsafe-return");
        String requestReference="unsafe-return-request-"+UUID.randomUUID();
        var request=owner.write("POST",base()+"/"+prepared.transferId()+"/request-return",Map.of(
                "requestId",UUID.randomUUID(),"expectedVersion",4,"requestReference",requestReference,
                "lines",java.util.List.of(Map.of("lineId",prepared.lineId())),
                "reason","Request physical return of goods with a later safety notice",
                "evidenceReference","carrier:unsafe-return-request"));
        assertThat(request.statusCode()).as(request.body()).isEqualTo(200);
        var safety=owner.write("POST","/api/v1/organizations/"+fixture.organization+"/lots/"+fixture.lot
                +"/safety-status-evidence",Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,
                        "reason","A verified damage notice arrived before the return was received",
                        "recallState","NOT_REPORTED","damageState","DAMAGED",
                        "provenance",Map.of("source","Carrier inspection","reference","carrier:return-damage")));
        assertThat(safety.statusCode()).as(safety.body()).isEqualTo(201);

        var returned=owner.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",
                confirmReturnRequest(UUID.randomUUID(),5,requestReference,"unsafe-return-receipt-"+UUID.randomUUID(),
                        prepared.lineId(),stockVersion(fixture.sourceStore,fixture.position),"unsafe-returned-line",
                        prepared.sku(),prepared.lotCode()));

        assertThat(returned.statusCode()).as(returned.body()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT on_hand || ':' || quarantined || ':' || available FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND id=:position")
                .param("org",fixture.organization).param("store",fixture.sourceStore).param("position",fixture.position)
                .query(String.class).single()).isEqualTo("5:2:3");
        assertThat(jdbc.sql("SELECT count(*) || ':' || coalesce(sum(remaining_quantity),0) FROM stockrescue.stock_allocation WHERE org_id=:org AND store_id=:store AND position_id=:position AND bucket='QUARANTINED' AND state='ACTIVE'")
                .param("org",fixture.organization).param("store",fixture.sourceStore).param("position",fixture.position)
                .query(String.class).single()).isEqualTo("1:2");
        assertThat(custody(prepared.transferId(),prepared.lineId())).isEqualTo("2:0:0:2:0");
    }

    @Test
    void failedPhysicalReturnEvidenceRollsBackStockCustodyAndOutbox() throws Exception {
        var manager=fixture.storeManager();
        var prepared=preparePickedTransfer(manager,2);
        dispatch(manager,prepared,3,"dispatch-return-rollback");
        String requestReference="return-rollback-request-"+UUID.randomUUID();
        var request=manager.write("POST",base()+"/"+prepared.transferId()+"/request-return",Map.of(
                "requestId",UUID.randomUUID(),"expectedVersion",4,"requestReference",requestReference,
                "lines",java.util.List.of(Map.of("lineId",prepared.lineId())),
                "reason","Request a physical return before checking atomic rollback",
                "evidenceReference","carrier:return-rollback-request"));
        assertThat(request.statusCode()).as(request.body()).isEqualTo(200);
        long outboxEventsBefore=jdbc.sql("SELECT count(*) FROM stockrescue.transfer_outbox_event WHERE org_id=:org AND transfer_id=:transfer")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single();
        long outboxDeliveriesBefore=jdbc.sql("SELECT count(*) FROM stockrescue.transfer_outbox_delivery WHERE org_id=:org AND transfer_id=:transfer")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single();
        UUID confirmationRequestId=UUID.randomUUID();
        String trigger="test_reject_transfer_return_document";
        String function="test_reject_transfer_return_document_fn";
        jdbc.sql("DROP TRIGGER IF EXISTS "+trigger+" ON stockrescue.transfer_physical_document_line").update();
        jdbc.sql("DROP FUNCTION IF EXISTS stockrescue."+function+"()").update();
        try {
            jdbc.sql("CREATE FUNCTION stockrescue."+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    +"IF NEW.transfer_id='"+prepared.transferId()+"'::uuid AND NEW.document_type='RETURN' THEN "
                    +"NEW.quantity:=NEW.quantity-1; END IF; RETURN NEW; END; $$").update();
            jdbc.sql("CREATE TRIGGER "+trigger+" BEFORE INSERT ON stockrescue.transfer_physical_document_line "
                    +"FOR EACH ROW EXECUTE FUNCTION stockrescue."+function+"()").update();

            var returned=manager.write("POST",base()+"/"+prepared.transferId()+"/confirm-return",
                    confirmReturnRequest(confirmationRequestId,5,requestReference,"rollback-receipt-"+UUID.randomUUID(),
                            prepared.lineId(),stockVersion(fixture.sourceStore,fixture.position),"rollback-return-line",
                            prepared.sku(),prepared.lotCode()));
            assertThat(returned.statusCode()).as(returned.body()).isEqualTo(409);
            assertThat(stockOnHand(fixture.sourceStore,fixture.sourceLocation)).isEqualTo(3);
            assertThat(custody(prepared.transferId(),prepared.lineId())).isEqualTo("2:0:0:0:2");
            assertThat(transfers.pendingReturn(fixture.organization,prepared.transferId(),prepared.lineId())).isPresent();
            assertThat(jdbc.sql("SELECT status || ':' || version FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                    .param("org",fixture.organization).param("transfer",prepared.transferId()).query(String.class).single())
                    .isEqualTo("RECONCILIATION_REQUIRED:5");
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_RETURN'")
                    .param("org",fixture.organization).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.inventory_command_receipt WHERE org_id=:org AND store_id=:store AND operation='TRANSFER_RETURN' AND request_id=:request")
                    .param("org",fixture.organization).param("store",fixture.sourceStore).param("request",confirmationRequestId)
                    .query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document WHERE org_id=:org AND transfer_id=:transfer AND document_type='RETURN'")
                    .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_custody_movement WHERE org_id=:org AND transfer_id=:transfer AND movement_type='RETURN'")
                    .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND transfer_id=:transfer AND operation='CONFIRM_RETURN'")
                    .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_outbox_event WHERE org_id=:org AND transfer_id=:transfer")
                    .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single())
                    .isEqualTo(outboxEventsBefore);
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_outbox_delivery WHERE org_id=:org AND transfer_id=:transfer")
                    .param("org",fixture.organization).param("transfer",prepared.transferId()).query(Long.class).single())
                    .isEqualTo(outboxDeliveriesBefore);
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS "+trigger+" ON stockrescue.transfer_physical_document_line").update();
            jdbc.sql("DROP FUNCTION IF EXISTS stockrescue."+function+"()").update();
        }
    }

    @Test
    void changedCustodyAllowsAnUpdatedReturnRequestForTheRemainingUnits() throws Exception {
        var manager = fixture.storeManager();
        var prepared = preparePickedTransfer(manager, 2);
        dispatch(manager, prepared, 3, "dispatch-return-adjustment");

        var firstRequest = manager.write("POST", base() + "/" + prepared.transferId() + "/request-return",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 4,
                        "requestReference", "return-request-before-loss-" + UUID.randomUUID(),
                        "lines", java.util.List.of(Map.of("lineId", prepared.lineId())),
                        "reason", "Request return of the outstanding line", "evidenceReference", "carrier:return-request"));
        assertThat(firstRequest.statusCode()).as(firstRequest.body()).isEqualTo(200);

        var loss = manager.write("POST", base() + "/" + prepared.transferId() + "/damage",
                damageRequest(prepared, 5, 1, "loss-during-return-" + UUID.randomUUID()));
        assertThat(loss.statusCode()).as(loss.body()).isEqualTo(200);
        assertThat(custody(prepared.transferId(), prepared.lineId())).isEqualTo("2:0:1:0:1");
        assertThat(jdbc.sql("SELECT observed_state->>'state' FROM stockrescue.transfer_reconciliation_evidence WHERE org_id=:org AND transfer_id=:transfer AND evidence_type='RETURN_SUPERSEDED'")
                .param("org",fixture.organization).param("transfer",prepared.transferId()).query(String.class).single()).isEqualTo("SUPERSEDED");
        assertThat(tree(manager.get(base()+"/"+prepared.transferId()+"/active-returns")).path("items")).isEmpty();

        var returnReference = "return-request-after-loss-" + UUID.randomUUID();
        var updatedRequest = manager.write("POST", base() + "/" + prepared.transferId() + "/request-return",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 6, "requestReference", returnReference,
                        "lines", java.util.List.of(Map.of("lineId", prepared.lineId())),
                        "reason", "Request the remaining unit after documenting loss",
                        "evidenceReference", "carrier:return-request-updated"));
        assertThat(updatedRequest.statusCode()).as(updatedRequest.body()).isEqualTo(200);

        var returned = manager.write("POST", base() + "/" + prepared.transferId() + "/confirm-return",
                Map.of("requestId", UUID.randomUUID(), "expectedVersion", 7, "requestReference", returnReference,
                        "documentReference", "return-receipt-after-loss-" + UUID.randomUUID(),
                        "lines", java.util.List.of(Map.of("lineId", prepared.lineId(), "quantity", 1,
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
    void multipleReceiptLinesShareOneDestinationStockPostingAndRecoverExactly() throws Exception {
        var manager = fixture.storeManager();
        long firstSourceVersion = fixture.seedSource(fixture.sourceLocation, 7);
        long secondSourceVersion = fixture.seedSource(fixture.secondSourceLocation, 5);
        var created = manager.write("POST", base(), Map.of("requestId", UUID.randomUUID(),
                "sourceStoreId", fixture.sourceStore, "destinationStoreId", fixture.destinationStore,
                "lines", java.util.List.of(
                        Map.of("sourcePositionId", fixture.position, "destinationLocationId", fixture.destinationLocation,
                                "quantity", 7, "expectedSourceVersion", firstSourceVersion),
                        Map.of("sourcePositionId", fixture.secondPosition, "destinationLocationId", fixture.destinationLocation,
                                "quantity", 5, "expectedSourceVersion", secondSourceVersion)),
                "reason", "Move two source positions to the same receiving shelf",
                "evidenceReference", "transfer:shared-destination"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(created).path("transferId").asString());
        assertThat(manager.write("POST", base() + "/" + transferId + "/approve",
                decision(UUID.randomUUID(), 0, "Reserve both source positions")).statusCode()).isEqualTo(200);
        assertThat(manager.write("POST", base() + "/" + transferId + "/start-pick",
                decision(UUID.randomUUID(), 1, "Start both physical picks")).statusCode()).isEqualTo(200);

        var lines = tree(manager.get(base() + "/" + transferId)).path("lines");
        var first = lines.get(0);
        var second = lines.get(1);
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            var picked = manager.write("POST", base() + "/" + transferId + "/pick",
                    pickRequest(UUID.randomUUID(), 2 + index, UUID.fromString(line.path("id").asString()),
                            "pick-shared-destination-" + index, line.path("requestedQuantity").asLong(),
                            line.path("sku").asString(), line.path("lotCode").asString()));
            assertThat(picked.statusCode()).as(picked.body()).isEqualTo(200);
        }
        var dispatched = manager.write("POST", base() + "/" + transferId + "/dispatch", Map.of(
                "requestId", UUID.randomUUID(), "expectedVersion", 4, "documentReference", "dispatch-shared-destination",
                "lines", java.util.List.of(
                        Map.of("lineId", first.path("id").asString(), "quantity", first.path("requestedQuantity").asLong(),
                                "observedSku", first.path("sku").asString(), "observedLotCode", first.path("lotCode").asString()),
                        Map.of("lineId", second.path("id").asString(), "quantity", second.path("requestedQuantity").asLong(),
                                "observedSku", second.path("sku").asString(), "observedLotCode", second.path("lotCode").asString())),
                "reason", "Hand off both source positions", "evidenceReference", "dispatch:shared-destination"));
        assertThat(dispatched.statusCode()).as(dispatched.body()).isEqualTo(200);

        long firstReceived = first.path("requestedQuantity").asLong() == 7 ? 6 : 4;
        long secondReceived = second.path("requestedQuantity").asLong() == 7 ? 6 : 4;
        assertThat(fixture.seedDestination(1)).isEqualTo(1);
        var conflictingVersions = receiptLines(first, second, firstReceived, secondReceived, 1, 2);
        var conflict = manager.write("POST", base() + "/" + transferId + "/receive", Map.of(
                "requestId", UUID.randomUUID(), "expectedVersion", 5, "documentReference", "receipt-shared-version-conflict",
                "physicallyReceivedAt", Instant.now(), "lines", conflictingVersions,
                "reason", "Reject conflicting versions for the same destination position",
                "evidenceReference", "receipt:version-conflict"));
        assertThat(conflict.statusCode()).as(conflict.body()).isEqualTo(409);
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_IN'")
                .param("org", fixture.organization).query(Long.class).single()).isZero();
        var staleVersion = manager.write("POST", base() + "/" + transferId + "/receive", Map.of(
                "requestId", UUID.randomUUID(), "expectedVersion", 5, "documentReference", "receipt-shared-stale-position",
                "physicallyReceivedAt", Instant.now(), "lines", receiptLines(first, second, firstReceived, secondReceived, 0, 0),
                "reason", "Reject destination versions made stale by a concurrent stock write",
                "evidenceReference", "receipt:stale-destination-version"));
        assertThat(staleVersion.statusCode()).as(staleVersion.body()).isEqualTo(409);
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_custody_movement WHERE org_id=:org AND transfer_id=:transfer AND movement_type='RECEIPT'")
                .param("org", fixture.organization).param("transfer", transferId).query(Long.class).single()).isZero();
        var invalidLines = new java.util.ArrayList<>(receiptLines(first, second, firstReceived, secondReceived, 1, 1));
        var invalidLine = new java.util.HashMap<String, Object>(invalidLines.getFirst());
        invalidLine.put("observedLotCode", "UNEXPECTED-LOT");
        invalidLines.set(0, invalidLine);
        var invalidGroupLine = manager.write("POST", base() + "/" + transferId + "/receive", Map.of(
                "requestId", UUID.randomUUID(), "expectedVersion", 5, "documentReference", "receipt-shared-invalid-line",
                "physicallyReceivedAt", Instant.now(), "lines", invalidLines,
                "reason", "Reject a bad observed lot without partial application",
                "evidenceReference", "receipt:shared-invalid-line"));
        assertThat(invalidGroupLine.statusCode()).as(invalidGroupLine.body()).isEqualTo(409);
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_IN'")
                .param("org", fixture.organization).query(Long.class).single()).isZero();

        var firstReceipt = Map.<String, Object>of("requestId", UUID.randomUUID(), "expectedVersion", 5,
                "documentReference", "receipt-shared-first", "physicallyReceivedAt", Instant.now(),
                "lines", receiptLines(first, second, firstReceived, secondReceived, 1, 1),
                "reason", "Count both received quantities on the shared shelf", "evidenceReference", "receipt:shared-first");
        var firstOutcome = manager.write("POST", base() + "/" + transferId + "/receive", firstReceipt);
        assertThat(firstOutcome.statusCode()).as(firstOutcome.body()).isEqualTo(200);
        assertThat(tree(firstOutcome).path("status").asString()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(11);
        assertThat(jdbc.sql("SELECT l.position_version || ':' || l.delta_on_hand FROM stockrescue.stock_movement_line l JOIN stockrescue.stock_movement m ON m.org_id=l.org_id AND m.store_id=l.store_id AND m.id=l.movement_id WHERE l.org_id=:org AND l.store_id=:store AND l.position_id=:position AND m.operation='TRANSFER_IN' ORDER BY l.position_version")
                .param("org", fixture.organization).param("store", fixture.destinationStore)
                .param("position", fixture.stockPosition(fixture.destinationStore, fixture.destinationLocation))
                .query(String.class).list()).containsExactly("2:10");
        assertThat(custody(transferId, UUID.fromString(first.path("id").asString())))
                .isEqualTo(first.path("requestedQuantity").asLong() + ":" + firstReceived + ":0:0:"
                        + (first.path("requestedQuantity").asLong() - firstReceived));
        assertThat(custody(transferId, UUID.fromString(second.path("id").asString())))
                .isEqualTo(second.path("requestedQuantity").asLong() + ":" + secondReceived + ":0:0:"
                        + (second.path("requestedQuantity").asLong() - secondReceived));

        var secondReceipt = Map.<String, Object>of("requestId", UUID.randomUUID(), "expectedVersion", 6,
                "documentReference", "receipt-shared-complete", "physicallyReceivedAt", Instant.now(),
                "lines", receiptLines(first, second, first.path("requestedQuantity").asLong() - firstReceived,
                        second.path("requestedQuantity").asLong() - secondReceived, 2, 2),
                "reason", "Count the remaining quantities on the shared shelf", "evidenceReference", "receipt:shared-complete");
        var completed = manager.write("POST", base() + "/" + transferId + "/receive", secondReceipt);
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        assertThat(tree(completed).path("status").asString()).isEqualTo("RECEIVED");
        assertThat(tree(manager.write("POST", base() + "/" + transferId + "/receive", secondReceipt)))
                .isEqualTo(tree(completed));
        assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isEqualTo(13);
        assertThat(jdbc.sql("SELECT l.position_version || ':' || l.delta_on_hand FROM stockrescue.stock_movement_line l JOIN stockrescue.stock_movement m ON m.org_id=l.org_id AND m.store_id=l.store_id AND m.id=l.movement_id WHERE l.org_id=:org AND l.store_id=:store AND l.position_id=:position AND m.operation='TRANSFER_IN' ORDER BY l.position_version")
                .param("org", fixture.organization).param("store", fixture.destinationStore)
                .param("position", fixture.stockPosition(fixture.destinationStore, fixture.destinationLocation))
                .query(String.class).list()).containsExactly("2:10", "3:2");
        assertThat(custody(transferId, UUID.fromString(first.path("id").asString())))
                .isEqualTo(first.path("requestedQuantity").asLong() + ":" + first.path("requestedQuantity").asLong() + ":0:0:0");
        assertThat(custody(transferId, UUID.fromString(second.path("id").asString())))
                .isEqualTo(second.path("requestedQuantity").asLong() + ":" + second.path("requestedQuantity").asLong() + ":0:0:0");
        assertThat(transfers.custodyLedgerMismatchCount()).isZero();
    }

    private static java.util.List<Map<String, Object>> receiptLines(tools.jackson.databind.JsonNode first,
            tools.jackson.databind.JsonNode second, long firstQuantity, long secondQuantity,
            long firstDestinationVersion, long secondDestinationVersion) {
        return java.util.List.of(
                Map.of("lineId", first.path("id").asString(), "quantity", firstQuantity,
                        "expectedDestinationVersion", firstDestinationVersion,
                        "documentLineReference", "receipt-line-" + UUID.randomUUID(),
                        "observedSku", first.path("sku").asString(), "observedLotCode", first.path("lotCode").asString()),
                Map.of("lineId", second.path("id").asString(), "quantity", secondQuantity,
                        "expectedDestinationVersion", secondDestinationVersion,
                        "documentLineReference", "receipt-line-" + UUID.randomUUID(),
                        "observedSku", second.path("sku").asString(), "observedLotCode", second.path("lotCode").asString()));
    }

    @Test
    void unsafeLinesSharingAReceiptPositionAreAllQuarantinedInOnePosting() throws Exception {
        var manager = fixture.storeManager();
        var owner = fixture.owner();
        var prepared = prepareSharedDestinationTransfer(manager, 2, 2);
        var recalled = owner.write("POST", "/api/v1/organizations/" + fixture.organization + "/lots/" + fixture.lot
                + "/safety-status-evidence", Map.of("requestId", UUID.randomUUID(), "expectedVersion", 0,
                        "reason", "A verified manufacturer recall arrived during transit", "recallState", "RECALLED",
                        "damageState", "NOT_REPORTED", "provenance", Map.of("source", "Manufacturer recall notice",
                                "reference", "recall:shared-receipt")));
        assertThat(recalled.statusCode()).as(recalled.body()).isEqualTo(201);

        var received = manager.write("POST", base() + "/" + prepared.transferId() + "/receive", Map.of(
                "requestId", UUID.randomUUID(), "expectedVersion", 5, "documentReference", "receipt-recalled-shared-group",
                "physicallyReceivedAt", Instant.now(), "lines", receiptLines(prepared.firstLine(), prepared.secondLine(), 2, 2, 0, 0),
                "reason", "Quarantine every recalled unit in the shared group",
                "evidenceReference", "receipt:recalled-shared-group"));
        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);
        assertThat(tree(received).path("status").asString()).isEqualTo("RECEIVED");
        assertThat(jdbc.sql("SELECT on_hand || ':' || quarantined || ':' || available FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND location_id=:location AND lot_id=:lot")
                .param("org", fixture.organization).param("store", fixture.destinationStore)
                .param("location", fixture.destinationLocation).param("lot", fixture.lot)
                .query(String.class).single()).isEqualTo("4:4:0");
        assertThat(jdbc.sql("SELECT delta_on_hand || ':' || delta_quarantined FROM stockrescue.stock_movement_line l JOIN stockrescue.stock_movement m ON m.org_id=l.org_id AND m.store_id=l.store_id AND m.id=l.movement_id WHERE l.org_id=:org AND m.operation='TRANSFER_IN'")
                .param("org", fixture.organization).query(String.class).single()).isEqualTo("4:4");
        assertThat(jdbc.sql("SELECT disposition FROM stockrescue.transfer_physical_document_line WHERE org_id=:org AND transfer_id=:transfer AND document_type='RECEIPT'")
                .param("org", fixture.organization).param("transfer", prepared.transferId())
                .query(String.class).list()).containsExactlyInAnyOrder("QUARANTINED", "QUARANTINED");
        assertThat(jdbc.sql("SELECT count(*) || ':' || sum(remaining_quantity) FROM stockrescue.stock_allocation WHERE org_id=:org AND store_id=:store AND bucket='QUARANTINED' AND state='ACTIVE'")
                .param("org", fixture.organization).param("store", fixture.destinationStore)
                .query(String.class).single()).isEqualTo("2:4");
    }

    @Test
    void postgresRejectsReceiptWhenGroupedStockAndCustodyQuantitiesDiverge() throws Exception {
        var manager = fixture.storeManager();
        var prepared = prepareSharedDestinationTransfer(manager, 2, 2);
        var corruptedLineId = UUID.fromString(prepared.firstLine().path("id").asString());
        String documentTrigger = "test_corrupt_transfer_receipt_document";
        String custodyTrigger = "test_corrupt_transfer_receipt_custody";
        String documentFunction = "test_corrupt_transfer_receipt_document_fn";
        String custodyFunction = "test_corrupt_transfer_receipt_custody_fn";
        jdbc.sql("DROP TRIGGER IF EXISTS " + documentTrigger + " ON stockrescue.transfer_physical_document_line").update();
        jdbc.sql("DROP TRIGGER IF EXISTS " + custodyTrigger + " ON stockrescue.transfer_custody_movement").update();
        jdbc.sql("DROP FUNCTION IF EXISTS stockrescue." + documentFunction + "()").update();
        jdbc.sql("DROP FUNCTION IF EXISTS stockrescue." + custodyFunction + "()").update();
        try {
            jdbc.sql("CREATE FUNCTION stockrescue." + documentFunction + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "IF NEW.transfer_id='" + prepared.transferId() + "'::uuid AND NEW.line_id='" + corruptedLineId
                    + "'::uuid AND NEW.document_type='RECEIPT' THEN NEW.quantity:=NEW.quantity-1; END IF; RETURN NEW; END; $$").update();
            jdbc.sql("CREATE FUNCTION stockrescue." + custodyFunction + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "IF NEW.transfer_id='" + prepared.transferId() + "'::uuid AND NEW.line_id='" + corruptedLineId
                    + "'::uuid AND NEW.movement_type='RECEIPT' THEN NEW.quantity:=NEW.quantity-1; "
                    + "NEW.outstanding_after:=NEW.outstanding_before-NEW.quantity; END IF; RETURN NEW; END; $$").update();
            jdbc.sql("CREATE TRIGGER " + documentTrigger + " BEFORE INSERT ON stockrescue.transfer_physical_document_line "
                    + "FOR EACH ROW EXECUTE FUNCTION stockrescue." + documentFunction + "()").update();
            jdbc.sql("CREATE TRIGGER " + custodyTrigger + " BEFORE INSERT ON stockrescue.transfer_custody_movement "
                    + "FOR EACH ROW EXECUTE FUNCTION stockrescue." + custodyFunction + "()").update();

            var receipt = manager.write("POST", base() + "/" + prepared.transferId() + "/receive", Map.of(
                    "requestId", UUID.randomUUID(), "expectedVersion", 5, "documentReference", "receipt-corrupt-group",
                    "physicallyReceivedAt", Instant.now(), "lines", receiptLines(prepared.firstLine(), prepared.secondLine(), 2, 2, 0, 0),
                    "reason", "Verify grouped custody matches the posted stock delta",
                    "evidenceReference", "receipt:corrupt-group"));
            assertThat(receipt.statusCode()).as(receipt.body()).isEqualTo(409);
            assertThat(tree(receipt).path("code").asString()).isEqualTo("CONFIGURATION_CONFLICT");
            assertThat(stockOnHand(fixture.destinationStore, fixture.destinationLocation)).isZero();
            assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                    .param("org", fixture.organization).param("transfer", prepared.transferId()).query(String.class).single())
                    .isEqualTo("IN_TRANSIT");
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement WHERE org_id=:org AND operation='TRANSFER_IN'")
                    .param("org", fixture.organization).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_physical_document WHERE org_id=:org AND transfer_id=:transfer AND document_type='RECEIPT'")
                    .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_custody_movement WHERE org_id=:org AND transfer_id=:transfer AND movement_type='RECEIPT'")
                    .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND transfer_id=:transfer AND operation='RECEIVE'")
                    .param("org", fixture.organization).param("transfer", prepared.transferId()).query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS " + documentTrigger + " ON stockrescue.transfer_physical_document_line").update();
            jdbc.sql("DROP TRIGGER IF EXISTS " + custodyTrigger + " ON stockrescue.transfer_custody_movement").update();
            jdbc.sql("DROP FUNCTION IF EXISTS stockrescue." + documentFunction + "()").update();
            jdbc.sql("DROP FUNCTION IF EXISTS stockrescue." + custodyFunction + "()").update();
        }
    }

    private PreparedSharedTransfer prepareSharedDestinationTransfer(Fixture.Browser manager,
            long firstQuantity, long secondQuantity) throws Exception {
        long firstSourceVersion = fixture.seedSource(fixture.sourceLocation, firstQuantity);
        long secondSourceVersion = fixture.seedSource(fixture.secondSourceLocation, secondQuantity);
        var created = manager.write("POST", base(), Map.of("requestId", UUID.randomUUID(),
                "sourceStoreId", fixture.sourceStore, "destinationStoreId", fixture.destinationStore,
                "lines", java.util.List.of(
                        Map.of("sourcePositionId", fixture.position, "destinationLocationId", fixture.destinationLocation,
                                "quantity", firstQuantity, "expectedSourceVersion", firstSourceVersion),
                        Map.of("sourcePositionId", fixture.secondPosition, "destinationLocationId", fixture.destinationLocation,
                                "quantity", secondQuantity, "expectedSourceVersion", secondSourceVersion)),
                "reason", "Move two source positions to the same receiving shelf",
                "evidenceReference", "transfer:shared-destination"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(created).path("transferId").asString());
        assertThat(manager.write("POST", base() + "/" + transferId + "/approve",
                decision(UUID.randomUUID(), 0, "Reserve both source positions")).statusCode()).isEqualTo(200);
        assertThat(manager.write("POST", base() + "/" + transferId + "/start-pick",
                decision(UUID.randomUUID(), 1, "Start both physical picks")).statusCode()).isEqualTo(200);
        var lines = tree(manager.get(base() + "/" + transferId)).path("lines");
        var first = lines.get(0);
        var second = lines.get(1);
        if (!fixture.position.equals(UUID.fromString(first.path("sourcePositionId").asString()))) {
            var reordered = first;
            first = second;
            second = reordered;
        }
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            var picked = manager.write("POST", base() + "/" + transferId + "/pick",
                    pickRequest(UUID.randomUUID(), 2 + index, UUID.fromString(line.path("id").asString()),
                            "pick-shared-destination-" + index, line.path("requestedQuantity").asLong(),
                            line.path("sku").asString(), line.path("lotCode").asString()));
            assertThat(picked.statusCode()).as(picked.body()).isEqualTo(200);
        }
        var dispatched = manager.write("POST", base() + "/" + transferId + "/dispatch", Map.of(
                "requestId", UUID.randomUUID(), "expectedVersion", 4, "documentReference", "dispatch-shared-destination",
                "lines", java.util.List.of(
                        Map.of("lineId", first.path("id").asString(), "quantity", first.path("requestedQuantity").asLong(),
                                "observedSku", first.path("sku").asString(), "observedLotCode", first.path("lotCode").asString()),
                        Map.of("lineId", second.path("id").asString(), "quantity", second.path("requestedQuantity").asLong(),
                                "observedSku", second.path("sku").asString(), "observedLotCode", second.path("lotCode").asString())),
                "reason", "Hand off both source positions", "evidenceReference", "dispatch:shared-destination"));
        assertThat(dispatched.statusCode()).as(dispatched.body()).isEqualTo(200);
        return new PreparedSharedTransfer(transferId, first, second);
    }

    private record PreparedSharedTransfer(UUID transferId, tools.jackson.databind.JsonNode firstLine,
            tools.jackson.databind.JsonNode secondLine) { }

    @Test
    void sourceOperatorCanCancelTheirOwnRequestAndRetryRequiresCurrentGrant() throws Exception {
        var operator = fixture.operator();
        var transfer = operator.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 1, 0, "Operator requested transfer"));
        assertThat(transfer.statusCode()).as(transfer.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(transfer).path("transferId").asString());
        var detailBeforeCancel = operator.get(base() + "/" + transferId);
        assertThat(detailBeforeCancel.statusCode()).as(detailBeforeCancel.body()).isEqualTo(200);
        assertThat(tree(detailBeforeCancel).path("canCancel").asBoolean()).isTrue();
        var reject = operator.write("POST", base() + "/" + transferId + "/reject", decision(UUID.randomUUID(), 0, "Operator cannot approve"));
        problem(reject, 403, "ACCESS_DENIED");

        var requestId = UUID.randomUUID();
        var request = decision(requestId, 0, "Owner withdrew the request");
        String intentsPath = "/api/v1/organizations/" + fixture.organization + "/transfer-command-intents";
        var preparedCancel = operator.write("POST", intentsPath, Map.of("prepareId", UUID.randomUUID(), "operation", "CANCEL",
                "transferId", transferId, "payload", request));
        assertThat(preparedCancel.statusCode()).as(preparedCancel.body()).isEqualTo(200);
        var cancelled = operator.write("POST", base() + "/" + transferId + "/cancel", request);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        UUID cancelIntentId = UUID.fromString(tree(preparedCancel).path("id").asString());
        assertThat(operator.write("POST", intentsPath + "/" + cancelIntentId + "/acknowledge", null).statusCode()).isEqualTo(200);
        assertThat(tree(operator.write("POST", base() + "/" + transferId + "/cancel", request)))
                .isEqualTo(tree(cancelled));
        assertThat(tree(operator.get(base() + "/" + transferId)).path("canCancel").asBoolean()).isFalse();

        jdbc.sql("UPDATE stockrescue.store_grant SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE membership_id=:membership AND store_id=:store")
                .param("membership", fixture.operatorMembership).param("store", fixture.sourceStore).update();
        problem(operator.write("POST", base() + "/" + transferId + "/cancel", request), 404, "RESOURCE_NOT_FOUND");
        assertThat(count("transfer_order", fixture.organization)).isEqualTo(1);
        assertThat(count("transfer_transition", fixture.organization)).isEqualTo(2);
        assertThat(count("inventory_command_receipt", fixture.organization)).isZero();
    }

    @Test
    void requesterPermissionDoesNotAuthorizeCancellationAfterPickingBegins() throws Exception {
        var operator = fixture.operator();
        var owner = fixture.owner();
        long sourceVersion = fixture.seedSource(5);
        var created = operator.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 1, sourceVersion,
                "Request stock for a store operation"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(created).path("transferId").asString());
        var requestDetail = operator.get(base() + "/" + transferId);
        assertThat(tree(requestDetail).path("canCancel").asBoolean()).isTrue();
        assertThat(owner.write("POST", base() + "/" + transferId + "/approve",
                decision(UUID.randomUUID(), 0, "Approve the store request")).statusCode()).isEqualTo(200);
        assertThat(owner.write("POST", base() + "/" + transferId + "/start-pick",
                decision(UUID.randomUUID(), 1, "Begin source picking")).statusCode()).isEqualTo(200);

        assertThat(tree(operator.get(base() + "/" + transferId)).path("canCancel").asBoolean()).isFalse();
        assertThat(tree(owner.get(base() + "/" + transferId)).path("canCancel").asBoolean()).isTrue();

        var requestId = UUID.randomUUID();
        problem(operator.write("POST", base() + "/" + transferId + "/cancel",
                cancelRequest(requestId, 2, "Requester lacks cancel authority after approval", null)),
                403, "ACCESS_DENIED");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", transferId).query(String.class).single()).isEqualTo("PICKING");
        UUID operatorId = jdbc.sql("SELECT user_id FROM stockrescue.membership WHERE org_id=:org AND id=:membership")
                .param("org", fixture.organization).param("membership", fixture.operatorMembership).query(UUID.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND transfer_id=:transfer AND actor_user_id=:actor AND operation='CANCEL'")
                .param("org", fixture.organization).param("transfer", transferId).param("actor", operatorId)
                .query(Long.class).single()).isZero();
        var cancelled = owner.write("POST", base() + "/" + transferId + "/cancel",
                decision(UUID.randomUUID(), 2, "Manager cancels before physical picking"));
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
    }

    @Test
    void requesterCannotCancelAnotherOwnersRequestOrTheirOwnAfterSourceGrantRevocation() throws Exception {
        var operator = fixture.operator();
        UUID operatorMembership = fixture.operatorMembership;
        var otherOwnersRequest = fixture.owner().write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 1, 0,
                "Create a request owned by another store actor"));
        assertThat(otherOwnersRequest.statusCode()).as(otherOwnersRequest.body()).isEqualTo(200);
        UUID otherTransferId = UUID.fromString(tree(otherOwnersRequest).path("transferId").asString());
        var operatorsRequest = operator.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 1, 0,
                "Create the request owned by this operator"));
        assertThat(operatorsRequest.statusCode()).as(operatorsRequest.body()).isEqualTo(200);
        UUID ownTransferId = UUID.fromString(tree(operatorsRequest).path("transferId").asString());

        problem(operator.write("POST", base() + "/" + otherTransferId + "/cancel",
                decision(UUID.randomUUID(), 0, "Try to cancel another owner's request")), 403, "ACCESS_DENIED");
        fixture.revokeGrant(operatorMembership, fixture.sourceStore);
        problem(operator.write("POST", base() + "/" + ownTransferId + "/cancel",
                decision(UUID.randomUUID(), 0, "Try to cancel own request after revocation")), 404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id IN (:first,:second)")
                .param("org", fixture.organization).param("first", otherTransferId).param("second", ownTransferId)
                .query(String.class).list()).containsExactlyInAnyOrder("REQUESTED", "REQUESTED");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND operation='CANCEL'")
                .param("org", fixture.organization).query(Long.class).single()).isZero();
    }

    @Test
    void requesterCancellationRechecksPermissionAfterConcurrentPickingBegins() throws Exception {
        var requester = fixture.operator();
        var owner = fixture.owner();
        long sourceVersion = fixture.seedSource(5);
        var created = requester.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, fixture.position, fixture.destinationLocation, 1, sourceVersion,
                "Request stock for a store operation"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(created).path("transferId").asString());
        UUID requesterId = jdbc.sql("SELECT user_id FROM stockrescue.membership WHERE org_id=:org AND id=:membership")
                .param("org", fixture.organization).param("membership", fixture.operatorMembership).query(UUID.class).single();
        var authorized = new CountDownLatch(1);
        var continueCancellation = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            var result = call.callRealMethod();
            authorized.countDown();
            if (!continueCancellation.await(15, TimeUnit.SECONDS))
                throw new IllegalStateException("Cancellation authorization barrier timed out");
            return result;
        }).when(accessPolicy).requireTransferStore(
                org.mockito.ArgumentMatchers.eq(requesterId),
                org.mockito.ArgumentMatchers.eq(fixture.organization),
                org.mockito.ArgumentMatchers.eq(fixture.sourceStore),
                org.mockito.ArgumentMatchers.eq(StoreAction.REQUEST_TRANSFER),
                org.mockito.ArgumentMatchers.eq(true));

        try (var workers = Executors.newFixedThreadPool(1)) {
            var cancellation = workers.submit(() -> requester.write("POST", base() + "/" + transferId + "/cancel",
                    cancelRequest(UUID.randomUUID(), 0, "Withdraw a request while approval is racing", null)));
            assertThat(authorized.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(owner.write("POST", base() + "/" + transferId + "/approve",
                    decision(UUID.randomUUID(), 0, "Approve the store request")).statusCode()).isEqualTo(200);
            assertThat(owner.write("POST", base() + "/" + transferId + "/start-pick",
                    decision(UUID.randomUUID(), 1, "Begin source picking")).statusCode()).isEqualTo(200);
            continueCancellation.countDown();
            assertThat(cancellation.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(403);
        } finally {
            continueCancellation.countDown();
        }

        assertThat(jdbc.sql("SELECT status FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer")
                .param("org", fixture.organization).param("transfer", transferId).query(String.class).single())
                .isEqualTo("PICKING");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND transfer_id=:transfer AND actor_user_id=:actor AND operation='CANCEL'")
                .param("org", fixture.organization).param("transfer", transferId).param("actor", requesterId)
                .query(Long.class).single()).isZero();
        assertThat(owner.write("POST", base() + "/" + transferId + "/cancel",
                decision(UUID.randomUUID(), 2, "Manager cancels before physical picking")).statusCode()).isEqualTo(200);
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

    private PreparedTransfer preparePickingTransfer(Fixture.Browser browser, UUID sourcePosition,
            UUID destinationLocation, long quantity, long sourceVersion) throws Exception {
        var created = browser.write("POST", base(), createRequest(UUID.randomUUID(), fixture.sourceStore,
                fixture.destinationStore, sourcePosition, destinationLocation, quantity, sourceVersion,
                "Request a documented whole-line movement"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        UUID transferId = UUID.fromString(tree(created).path("transferId").asString());
        assertThat(browser.write("POST", base() + "/" + transferId + "/approve",
                decision(UUID.randomUUID(), 0, "Reserve the current source units")).statusCode()).isEqualTo(200);
        var line = tree(browser.get(base() + "/" + transferId)).path("lines").get(0);
        UUID lineId = UUID.fromString(line.path("id").asString());
        UUID allocationId = UUID.fromString(line.path("reservationAllocationId").asString());
        var picking = browser.write("POST", base() + "/" + transferId + "/start-pick",
                decision(UUID.randomUUID(), 1, "Begin whole-line physical picking"));
        assertThat(picking.statusCode()).as(picking.body()).isEqualTo(200);
        return new PreparedTransfer(transferId, lineId, allocationId, quantity,
                line.path("sku").asString(), line.path("lotCode").asString(),
                line.path("expectedSourceVersion").asLong());
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
        assertTransferEvent(transferId, 0, "TransferCommandRecorded", "CREATE", "REQUESTED");
        var approved = browser.write("POST", base() + "/" + transferId + "/approve",
                decision(UUID.randomUUID(), 0, "Reserve the current source units"));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertTransferEvent(transferId, 1, "TransferCommandRecorded", "APPROVE", "APPROVED");
        var line = tree(browser.get(base() + "/" + transferId)).path("lines").get(0);
        UUID lineId = UUID.fromString(line.path("id").asString());
        UUID allocationId = UUID.fromString(line.path("reservationAllocationId").asString());
        var picking = browser.write("POST", base() + "/" + transferId + "/start-pick",
                decision(UUID.randomUUID(), 1, "Begin whole-line physical picking"));
        assertThat(picking.statusCode()).as(picking.body()).isEqualTo(200);
        assertTransferEvent(transferId, 2, "TransferCommandRecorded", "START_PICK", "PICKING");
        var picked = browser.write("POST", base() + "/" + transferId + "/pick",
                pickRequest(UUID.randomUUID(), 2, lineId, "pick-slip-" + UUID.randomUUID(), quantity,
                        line.path("sku").asString(), line.path("lotCode").asString()));
        assertThat(picked.statusCode()).as(picked.body()).isEqualTo(200);
        assertTransferEvent(transferId, 3, "TransferCommandRecorded", "PICK", "PICKED");
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
        assertTransferEvent(transfer.transferId(), expectedVersion + 1, "TransferDispatched", "DISPATCH", "IN_TRANSIT");
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

    private Map<String,Object> confirmReturnRequest(UUID requestId,long expectedTransferVersion,String requestReference,
            String documentReference,UUID lineId,long expectedSourceVersion,String documentLineReference,String sku,String lotCode) {
        UUID transferId=jdbc.sql("SELECT transfer_id FROM stockrescue.transfer_line WHERE org_id=:org AND id=:line")
                .param("org",fixture.organization).param("line",lineId).query(UUID.class).single();
        long quantity=transfers.pendingReturn(fixture.organization,transferId,lineId).orElseThrow().quantity();
        return confirmReturnRequest(requestId,expectedTransferVersion,requestReference,documentReference,lineId,quantity,
                expectedSourceVersion,documentLineReference,sku,lotCode);
    }

    private Map<String,Object> confirmReturnRequest(UUID requestId,long expectedTransferVersion,String requestReference,
            String documentReference,UUID lineId,long quantity,long expectedSourceVersion,String documentLineReference,String sku,String lotCode) {
        return Map.of("requestId",requestId,"expectedVersion",expectedTransferVersion,"requestReference",requestReference,
                "documentReference",documentReference,"lines",java.util.List.of(Map.of("lineId",lineId,"quantity",quantity,
                        "expectedSourceVersion",expectedSourceVersion,"documentLineReference",documentLineReference,
                        "observedSku",sku,"observedLotCode",lotCode)),
                "reason","Counted the exact physical line returned to the source","evidenceReference","return-receipt:"+documentReference);
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

    private void rejectPreparedIntentReceipt(UUID transferId, UUID actorId, UUID membershipId, UUID requestId,
            byte[] fingerprint) {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO stockrescue.transfer_command_receipt
                    (id,org_id,transfer_id,actor_user_id,membership_id,operation,request_id,payload_digest,outcome)
                VALUES (:id,:org,:transfer,:actor,:membership,'CLOSE',:request,:fingerprint,'{}'::jsonb)
                """)
                .param("id", UUID.randomUUID()).param("org", fixture.organization).param("transfer", transferId)
                .param("actor", actorId).param("membership", membershipId).param("request", requestId)
                .param("fingerprint", fingerprint).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private static Map<String, Object> cancelRequest(UUID requestId, long version, String reason,
            String putBackDocumentReference) {
        var request = new java.util.HashMap<String, Object>(decision(requestId, version, reason));
        if (putBackDocumentReference != null) {
            request.put("putBackConfirmed", true);
            request.put("putBackDocumentReference", putBackDocumentReference);
        }
        return request;
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

    private static java.util.List<String> textValues(JsonNode array, String field) {
        var values = new java.util.ArrayList<String>();
        array.forEach(item -> values.add(item.path(field).asString()));
        return values;
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

    private java.util.List<HttpResponse<String>> withSettlementAuthorizationBarrier(Callable<HttpResponse<String>> first,
            Callable<HttpResponse<String>> second) throws Exception {
        var bothAuthorized=new CountDownLatch(2);
        var continueSettlements=new CountDownLatch(1);
        var gatedThreads=ConcurrentHashMap.<Long>newKeySet();
        org.mockito.Mockito.doAnswer(call -> {
            var authorized=call.callRealMethod();
            if(gatedThreads.add(Thread.currentThread().threadId())) {
                bothAuthorized.countDown();
                if(!continueSettlements.await(15,TimeUnit.SECONDS))
                    throw new IllegalStateException("Settlement authorization barrier timed out");
            }
            return authorized;
        }).when(accessPolicy).requireTransferSettlementStore(
                org.mockito.ArgumentMatchers.any(UUID.class),org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.any(UUID.class),org.mockito.ArgumentMatchers.any(StoreAction.class));
        try(var workers=Executors.newFixedThreadPool(2)) {
            var one=workers.submit(first);
            var two=workers.submit(second);
            assertThat(bothAuthorized.await(10,TimeUnit.SECONDS)).as("both requests held only shared participant-store locks before proceeding").isTrue();
            continueSettlements.countDown();
            return java.util.List.of(one.get(30,TimeUnit.SECONDS),two.get(30,TimeUnit.SECONDS));
        } finally {
            continueSettlements.countDown();
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
