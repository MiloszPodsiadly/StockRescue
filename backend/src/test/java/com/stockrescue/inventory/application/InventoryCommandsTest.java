package com.stockrescue.inventory.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.access.application.StaffService;
import com.stockrescue.catalog.application.*;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmation.ProductProfile;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmation.SourceType;
import com.stockrescue.catalog.domain.EvidenceProvenance;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.inventory.application.InventoryCommands.*;
import com.stockrescue.inventory.application.InventoryChangeProcessor;
import com.stockrescue.inventory.domain.InventoryFailure;
import com.stockrescue.inventory.domain.InventoryFailure.Kind;
import com.stockrescue.inventory.domain.StockBuckets;
import com.stockrescue.inventory.persistence.InventoryRepository;
import com.stockrescue.inventory.persistence.InventoryRepository.CommandReceipt;
import com.stockrescue.inventory.persistence.InventoryRepository.Movement;
import com.stockrescue.inventory.persistence.InventoryRepository.Operation;
import com.stockrescue.inventory.messaging.InventoryChangeListener;
import com.stockrescue.inventory.messaging.InventoryEventCodec;
import com.stockrescue.inventory.messaging.InventoryMessagingMetrics;
import com.stockrescue.inventory.messaging.InventoryOutboxRelay;
import com.stockrescue.inventory.messaging.InventoryStockChangedEvent;
import com.stockrescue.inventory.persistence.InventoryOutboxRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.Acknowledgment;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

// Let all synchronized commands reach PostgreSQL rather than timing out in the small connection pool.
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.datasource.hikari.connection-timeout=30000",
        "stockrescue.inventory.outbox-relay.enabled=false"})
@Import(InventoryCommandsTest.TimeConfiguration.class)
class InventoryCommandsTest extends InfrastructureTestBase {
    private static final Instant NOW=Instant.parse("2026-11-12T10:00:00Z");
    @Autowired private InventoryService service;
    @Autowired private InventoryReconciliationService reconciliation;
    @Autowired private InventoryRepository inventory;
    @Autowired private AccessPolicy access;
    @Autowired private FoodSafetyEvaluationService safety;
    @Autowired private FoodSafetyProductClassificationConfirmationService classification;
    @Autowired private LotService lots;
    @Autowired private StaffService staff;
    @Autowired private JdbcClient jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private TestClock clock;
    @Autowired private InventoryChangeProcessor changeProcessor;
    @Autowired private InventoryEventCodec eventCodec;
    @Autowired private InventoryOutboxRepository outbox;
    @Autowired private InventoryMessagingMetrics messagingMetrics;
    @MockitoSpyBean private KafkaTemplate kafka;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private InventoryOutboxRelay relay;
    @Autowired private InventoryChangeListener changeListener;
    @MockitoSpyBean private DataSource dataSource;
    private Fixture f;

    @BeforeEach void prepare() {
        clock.set(NOW); MDC.put("correlationId",UUID.randomUUID().toString());
        // The shared Testcontainers database is append-only; deliver earlier fixtures before
        // tests that make claims globally so unrelated pending events cannot starve them.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
            relay.dispatchOnce();
            assertThat(outbox.pendingCount()).isZero();
        });
        f=new Fixture();
    }
    @AfterEach void clear() { MDC.remove("correlationId"); clock.set(NOW); }

    @Test void oneHundredSynchronizedReservationsContendForOneUnitWithoutOverselling() throws Exception {
        var pool=dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
        int databaseCapacity=jdbc.sql("SELECT current_setting('max_connections')::int-current_setting('superuser_reserved_connections')::int-current_setting('reserved_connections')::int")
                .query(Integer.class).single();
        assertThat(pool.getMaximumPoolSize()).isLessThan(databaseCapacity);
        var received=receive(1); var ready=new CountDownLatch(100); var start=new CountDownLatch(1);
        try (var workers=Executors.newFixedThreadPool(100,Thread.ofVirtual().factory())) {
            var results=new ArrayList<java.util.concurrent.Future<Attempt>>();
            for(int index=0;index<100;index++) {
                var request=change(received.positionId(),1);
                results.add(workers.submit(()->{
                    ready.countDown(); if(!start.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Reservation barrier timed out");
                    return attempt(()->service.reserveStock(f.operator,f.org,f.store,request));
                }));
            }
            assertThat(ready.await(15,TimeUnit.SECONDS)).isTrue(); start.countDown();
            var outcomes=new ArrayList<Attempt>();
            for(var result:results) outcomes.add(result.get(30,TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(a->a.result!=null).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(a->a.failure==Kind.INSUFFICIENT_STOCK).count()).isEqualTo(99);
        } finally { start.countDown(); }
        assertThat(snapshot(received).buckets()).isEqualTo(new StockBuckets(1,1,0,0));
        assertThat(count("stock_allocation")).isEqualTo(1);
        assertThat(count("stock_movement")).isEqualTo(2);
        assertThat(count("inventory_outbox_event")).isEqualTo(2);
        reconstruct(received);
    }

    @Test void concurrentFirstReceiptsReuseThePositionCreatedAfterBothInitialReads() throws Exception {
        var reference=f.reference();var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            Callable<com.stockrescue.inventory.domain.StockPosition> create=()->transactions.execute(status->{
                inventory.lockStoreForWrite(f.org,f.store);
                inventory.lockActiveContext(f.org,f.store,reference.locationId(),reference.productId(),reference.lotId());
                assertThat(inventory.positionByIdentity(f.org,f.store,reference.locationId(),reference.lotId())).isEmpty();
                ready.countDown();barrier(start);
                return inventory.createPosition(UUID.randomUUID(),f.org,f.store,reference.locationId(),reference.productId(),reference.lotId());
            });
            var first=workers.submit(create);var second=workers.submit(create);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            var firstPosition=first.get(15,TimeUnit.SECONDS);var secondPosition=second.get(15,TimeUnit.SECONDS);
            assertThat(secondPosition.id()).isEqualTo(firstPosition.id());
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND location_id=:location AND lot_id=:lot")
                    .param("org",f.org).param("store",f.store).param("location",reference.locationId()).param("lot",reference.lotId())
                    .query(Long.class).single()).isEqualTo(1);
        } finally { start.countDown(); }
    }

    @Test void aLostSaleResponseReplaysItsOriginalOutcomeAfterLaterCommands() {
        var received=receive(5); var request=change(received.positionId(),1);
        var sold=service.recordSale(f.operator,f.org,f.store,request);
        service.recordSale(f.operator,f.org,f.store,change(received.positionId(),1));
        assertThat(service.recordSale(f.operator,f.org,f.store,request)).isEqualTo(sold);
        assertThat(snapshot(received).buckets().onHand()).isEqualTo(3);
        assertThat(count("stock_movement")).isEqualTo(3);
        assertThat(count("inventory_outbox_event")).isEqualTo(3);
        var changed=new StockChange(request.requestId(),request.positionId(),request.expectedVersion(),2,request.reason(),request.evidenceReference());
        fails(Kind.IDEMPOTENCY_CONFLICT,()->service.recordSale(f.operator,f.org,f.store,changed));
        reconstruct(received);
    }

    @Test void simultaneousRetriesOfTheSameSaleCommitOneLogicalOutcome() throws Exception {
        var received=receive(3);var command=change(received.positionId(),1);
        var results=race(()->service.recordSale(f.operator,f.org,f.store,command),()->service.recordSale(f.operator,f.org,f.store,command));
        assertThat(results).allMatch(result->result.failure==null);
        assertThat(results.getFirst().result).isEqualTo(results.getLast().result);
        assertThat(snapshot(received).buckets().onHand()).isEqualTo(2);
        assertThat(count("stock_movement")).isEqualTo(2);assertThat(count("inventory_outbox_event")).isEqualTo(2);
    }

    @Test void receiptEvidenceIsRequiredBeforeAnyPhysicalUnitsAreRecorded() {
        var valid=receipt(f.reference(),1);
        var missing=new ReceiveGoods(valid.requestId(),valid.position(),1,valid.supplierKey(),valid.documentType()," ",valid.documentLineReference(),valid.physicallyReceivedAt(),valid.reason(),valid.evidenceReference());
        fails(Kind.INVALID_REQUEST,()->service.receiveGoods(f.operator,f.org,f.store,missing));
        var future=new ReceiveGoods(valid.requestId(),valid.position(),1,valid.supplierKey(),valid.documentType(),valid.documentReference(),valid.documentLineReference(),NOW.plusSeconds(1),valid.reason(),valid.evidenceReference());
        fails(Kind.INVALID_REQUEST,()->service.receiveGoods(f.operator,f.org,f.store,future));
        assertThat(count("stock_position")).isZero();assertThat(count("stock_movement")).isZero();assertThat(count("goods_receipt")).isZero();
    }

    @Test void inventoryListsUseTheSameNumberOfDatabaseStatementsForOneOrManyPositions() throws Exception {
        receive(1);
        var statements=new ArrayList<String>();
        // Wrap real pooled connections solely to count prepared SQL, retaining actual PostgreSQL execution.
        doAnswer(call->{
            var connection=(java.sql.Connection)call.callRealMethod();
            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement")) statements.add((String)args[0]);
                try { return method.invoke(connection,args); }
                catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
        }).when(dataSource).getConnection();
        try {
            assertThat(service.positions(f.analyst,f.org,f.store,f.lot,0,100).items()).hasSize(1);
            int single=statements.size();
            for(int index=0;index<10;index++) {
                var location=UUID.randomUUID();
                sql("INSERT INTO stockrescue.storage_location(id,org_id,store_id,zone_id,code,name) VALUES (?,?,?,?,'ROW-"+index+"','Additional shelf')",location,f.org,f.store,f.zone);
                service.receiveGoods(f.owner,f.org,f.store,receipt(new PositionReference(location,f.product,f.lot),1));
            }
            statements.clear();
            assertThat(service.positions(f.analyst,f.org,f.store,f.lot,0,100).items()).hasSize(11);
            assertThat(statements).hasSize(single);
            assertThat(statements.stream().filter(sql->sql.contains("FROM stockrescue.stock_position")).count()).isEqualTo(1);
        } finally { doCallRealMethod().when(dataSource).getConnection(); }
    }

    @Test void aNegativeAdjustmentThatExceedsAvailableStockRollsBackItsEntireCommand() {
        var received=receive(1); long before=count("stock_movement");
        fails(Kind.INSUFFICIENT_STOCK,()->service.adjustStock(f.owner,f.org,f.store,change(received.positionId(),-2)));
        assertThat(snapshot(received).buckets()).isEqualTo(new StockBuckets(1,0,0,0));
        assertThat(count("stock_movement")).isEqualTo(before);
        assertThat(count("inventory_command_receipt")).isEqualTo(before);
        assertThat(count("inventory_outbox_event")).isEqualTo(before);
        assertThat(stockAudits()).isEqualTo(before);
    }

    @Test void simultaneousReservationAndQuarantineNeverAllocateTheSameUnit() throws Exception {
        var received=receive(1);
        var outcomes=race(()->service.reserveStock(f.operator,f.org,f.store,change(received.positionId(),1)),
                ()->service.quarantine(f.operator,f.org,f.store,change(received.positionId(),1)));
        assertThat(outcomes.stream().filter(a->a.result!=null).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(a->a.failure==Kind.INSUFFICIENT_STOCK).count()).isEqualTo(1);
        var buckets=snapshot(received).buckets();
        assertThat(buckets.reserved()+buckets.quarantined()).isEqualTo(1);
        assertThat(buckets.onHand()).isEqualTo(1); assertThat(buckets.available()).isZero();
        reconstruct(received);
    }

    @Test void expiredRecalledAndUnknownLotsCannotBeSoldEvenAfterAnEarlierAllowedReview() {
        var received=receive(3);
        assertThat(safety.evaluate(f.owner,f.org,f.lot,f.store,null,f.location,FoodSafetyEvaluation.Operation.SALE).decision())
                .isEqualTo(FoodSafetyEvaluation.Decision.ALLOWED);
        clock.set(Instant.parse("2027-05-01T12:00:00Z"));
        fails(Kind.LOT_NOT_ELIGIBLE,()->service.recordSale(f.owner,f.org,f.store,change(received.positionId(),1)));
        clock.set(NOW); recordRecall();
        fails(Kind.LOT_NOT_ELIGIBLE,()->service.recordSale(f.owner,f.org,f.store,change(received.positionId(),1)));
        var unknown=UUID.randomUUID();
        sql("INSERT INTO stockrescue.manufacturer_lot(id,org_id,product_id,lot_code) VALUES (?,?,?,'UNKNOWN')",unknown,f.org,f.product);
        var request=receipt(new PositionReference(f.location,f.product,unknown),2);
        var quarantined=service.receiveGoods(f.operator,f.org,f.store,request);
        assertThat(quarantined.buckets()).isEqualTo(new StockBuckets(2,0,2,0));
        fails(Kind.LOT_NOT_ELIGIBLE,()->service.recordSale(f.operator,f.org,f.store,change(quarantined.positionId(),1)));
        fails(Kind.LOT_NOT_ELIGIBLE,()->service.releaseHold(f.operator,f.org,f.store,new AllocationChange(change(quarantined.positionId(),1),quarantined.allocationId())));
        assertThat(count("stock_movement")).isEqualTo(2);
        assertThat(snapshot(received).buckets().onHand()).isEqualTo(3);
    }

    @Test void forgedOrganizationStoreProductLotAndLocationReferencesLeaveNoStockOrReceipt() {
        var received=receive(2); long before=count("stock_movement");
        assertThatThrownBy(()->service.snapshot(f.foreign,f.org,f.store,received.positionId())).isInstanceOf(OrganizationUnavailableException.class);
        assertThatThrownBy(()->service.reserveStock(f.owner,f.org,f.otherStore,change(received.positionId(),1))).isInstanceOf(OrganizationUnavailableException.class);
        for(var ref:List.of(new PositionReference(f.otherLocation,f.product,f.lot),new PositionReference(f.location,UUID.randomUUID(),f.lot),
                new PositionReference(f.location,f.product,UUID.randomUUID()))) {
            assertThatThrownBy(()->service.receiveGoods(f.owner,f.org,f.store,receipt(ref,1))).isInstanceOf(OrganizationUnavailableException.class);
        }
        assertThatThrownBy(()->service.receiveGoods(f.owner,f.otherOrg,f.store,receipt(f.reference(),1))).isInstanceOf(OrganizationUnavailableException.class);
        assertThat(count("stock_movement")).isEqualTo(before);
        assertThat(count("goods_receipt")).isEqualTo(1); assertThat(count("stock_position")).isEqualTo(1);
        assertThat(count("inventory_outbox_event")).isEqualTo(before);
    }

    @Test void aPhysicalDeliveryCannotBePostedAgainUnderAnotherRequestOrActor() {
        var command=receipt(f.reference(),2);
        var received=service.receiveGoods(f.owner,f.org,f.store,command);
        assertThat(service.receiveGoods(f.owner,f.org,f.store,command)).isEqualTo(received);
        var duplicate=new ReceiveGoods(UUID.randomUUID(),command.position(),command.quantity(),command.supplierKey(),command.documentType(),
                command.documentReference(),command.documentLineReference(),command.physicallyReceivedAt(),command.reason(),command.evidenceReference());
        fails(Kind.DOCUMENT_ALREADY_RECEIVED,()->service.receiveGoods(f.operator,f.org,f.store,duplicate));
        assertThat(count("stock_movement")).isEqualTo(1); assertThat(count("goods_receipt")).isEqualTo(1);
        assertThat(count("inventory_command_receipt")).isEqualTo(1); assertThat(count("inventory_outbox_event")).isEqualTo(1);
        assertThat(snapshot(received).buckets().onHand()).isEqualTo(2);
    }

    @Test void reservationOwnershipPartialReleaseAndDoubleReleaseRemainDistinctFromQuarantine() {
        var received=receive(4);
        var reserved=service.reserveStock(f.operator,f.org,f.store,change(received.positionId(),2));
        var quarantined=service.quarantine(f.operator,f.org,f.store,change(received.positionId(),2));
        var release=new AllocationChange(change(received.positionId(),1),reserved.allocationId());
        assertThatThrownBy(()->service.releaseReservation(f.peer,f.org,f.store,release)).isInstanceOf(AccessDeniedException.class);
        var released=service.releaseReservation(f.operator,f.org,f.store,release);
        assertThat(service.releaseReservation(f.operator,f.org,f.store,release)).isEqualTo(released);
        service.releaseReservation(f.manager,f.org,f.store,new AllocationChange(change(received.positionId(),1),reserved.allocationId()));
        fails(Kind.ALLOCATION_UNAVAILABLE,()->service.releaseReservation(f.operator,f.org,f.store,new AllocationChange(change(received.positionId(),1),reserved.allocationId())));
        fails(Kind.ALLOCATION_UNAVAILABLE,()->service.releaseReservation(f.operator,f.org,f.store,new AllocationChange(change(received.positionId(),1),quarantined.allocationId())));
        assertThat(snapshot(received).buckets()).isEqualTo(new StockBuckets(4,0,2,0));
        reconstruct(received);
    }

    @Test void aRealisticCommandHistoryReconstructsPhysicalUnitsAndAllAllocationBuckets() {
        var received=receive(10);
        var reserved=service.reserveStock(f.operator,f.org,f.store,change(received.positionId(),3));
        var quarantine=service.quarantine(f.operator,f.org,f.store,change(received.positionId(),2));
        var held=service.hold(f.operator,f.org,f.store,change(received.positionId(),1));
        service.releaseReservation(f.operator,f.org,f.store,new AllocationChange(change(received.positionId(),1),reserved.allocationId()));
        service.writeOff(f.manager,f.org,f.store,new AllocationChange(change(received.positionId(),1),quarantine.allocationId()));
        service.releaseHold(f.operator,f.org,f.store,new AllocationChange(change(received.positionId(),1),held.allocationId()));
        service.recordSale(f.operator,f.org,f.store,change(received.positionId(),2));
        service.adjustStock(f.manager,f.org,f.store,change(received.positionId(),2));
        service.adjustStock(f.manager,f.org,f.store,change(received.positionId(),-1));
        service.writeOff(f.manager,f.org,f.store,new AllocationChange(change(received.positionId(),1),reserved.allocationId()));
        assertThat(snapshot(received).buckets()).isEqualTo(new StockBuckets(7,1,1,0));
        assertThat(snapshot(received).version()).isEqualTo(11);
        assertThat(stockAudits()).isEqualTo(11); assertThat(count("inventory_outbox_event")).isEqualTo(11);
        reconstruct(received);
        var first=service.history(f.analyst,f.org,f.store,received.positionId(),0,3);
        assertThat(first.hasMore()).isTrue(); assertThat(first.items()).hasSize(3);
        assertThat(service.history(f.analyst,f.org,f.store,received.positionId(),3,100).items()).hasSize(8);
        assertThat(service.positions(f.analyst,f.org,f.store,f.lot,0,100).items()).hasSize(1);
        assertThat(reconciliation.reconcile(f.owner,f.org,f.store).severity()).isEqualTo("CLEAN");
    }

    @Test void additionalPhysicalUnitsOnABlockedLotAreQuarantinedAndCanBeWrittenOff() {
        var received=receive(2); recordRecall();
        var adjusted=service.adjustStock(f.manager,f.org,f.store,change(received.positionId(),3));
        assertThat(adjusted.buckets()).isEqualTo(new StockBuckets(5,0,3,0));
        service.writeOff(f.manager,f.org,f.store,new AllocationChange(change(received.positionId(),3),adjusted.allocationId()));
        assertThat(snapshot(received).buckets()).isEqualTo(new StockBuckets(2,0,0,0));
        fails(Kind.LOT_NOT_ELIGIBLE,()->service.recordSale(f.owner,f.org,f.store,change(received.positionId(),1)));
        reconstruct(received);
    }

    @Test void staleVersionsInvalidAmountsAndOverflowDoNotCreateCommandEvidence() {
        var received=receive(Long.MAX_VALUE); var initial=snapshot(received);
        fails(Kind.CONFLICT,()->service.adjustStock(f.owner,f.org,f.store,change(received.positionId(),1)));
        fails(Kind.INVALID_REQUEST,()->service.reserveStock(f.owner,f.org,f.store,change(received.positionId(),Long.MIN_VALUE)));
        fails(Kind.INVALID_REQUEST,()->service.recordSale(f.owner,f.org,f.store,change(received.positionId(),0)));
        var stale=new StockChange(UUID.randomUUID(),received.positionId(),0L,1,"Stale review","count-sheet");
        fails(Kind.CONFLICT,()->service.recordSale(f.owner,f.org,f.store,stale));
        assertThat(snapshot(received)).isEqualTo(initial); assertThat(count("stock_movement")).isEqualTo(1);
    }

    @Test void ordinaryWritesFailClosedForAFrozenPositionWhileAnotherPositionRemainsUsable() {
        var received=receive(2);
        transactions.executeWithoutResult(status->{
            access.requireStore(f.owner,f.org,f.store,StoreAction.RECONCILE_INVENTORY,true);
            var command=new CommandReceipt(UUID.randomUUID(),f.org,f.store,f.owner,f.ownerMember,Operation.RECONCILE,UUID.randomUUID(),new byte[32],"{}");
            inventory.recordCommand(command);var movement=UUID.randomUUID();inventory.recordMovement(movement,command,"Investigate discrepancy","reconciliation-run");
            inventory.postLine(f.org,f.store,movement,received.positionId(),received.positionVersion(),new StockBuckets.Delta(0,0,0,0),com.stockrescue.inventory.domain.StockPosition.Trust.FROZEN);
        });
        fails(Kind.POSITION_FROZEN,()->service.recordSale(f.owner,f.org,f.store,change(received.positionId(),1)));
        var second=service.receiveGoods(f.owner,f.org,f.otherStore,receipt(new PositionReference(f.otherLocation,f.product,f.lot),1));
        assertThat(service.recordSale(f.owner,f.org,f.otherStore,change(second.positionId(),1)).buckets().onHand()).isZero();
    }

    @Test void cleanReconciliationRebuildsEveryPositionFromMovementAndAllocationEvidence() {
        var received=receive(5);
        var reserved=service.reserveStock(f.operator,f.org,f.store,change(received.positionId(),2));
        service.releaseReservation(f.operator,f.org,f.store,new AllocationChange(change(received.positionId(),1),reserved.allocationId()));
        var run=reconciliation.reconcile(f.owner,f.org,f.store);
        assertThat(run.severity()).isEqualTo("CLEAN");
        assertThat(run.checkedPositions()).isEqualTo(1);
        var result=reconciliation.findings(f.analyst,f.org,f.store,run.id(),0,10);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.items()).singleElement().satisfies(finding->{
            assertThat(finding.severity()).isEqualTo("CLEAN");
            assertThat(finding.code()).isEqualTo("BALANCES_MATCH");
            assertThat(finding.positionVersion()).isEqualTo(snapshot(received).version());
        });
        assertThatThrownBy(()->reconciliation.findings(f.owner,f.org,f.otherStore,run.id(),0,10))
                .isInstanceOf(OrganizationUnavailableException.class);
        assertThatThrownBy(()->reconciliation.runs(f.foreign,f.otherOrg,f.store,0,10))
                .isInstanceOf(OrganizationUnavailableException.class);
        var secondRun=reconciliation.reconcile(f.owner,f.org,f.store);
        assertThat(secondRun.severity()).isEqualTo("CLEAN");
        var firstPage=reconciliation.runs(f.analyst,f.org,f.store,0,1);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.hasMore()).isTrue();
    }

    @Test void anUninitializedPositionIsWarnedAboutInsteadOfReportedAsVerifiedZero() {
        UUID position=UUID.randomUUID();
        transactions.executeWithoutResult(status->inventory.createPosition(position,f.org,f.store,f.location,f.product,f.lot));
        var run=reconciliation.reconcile(f.owner,f.org,f.store);
        assertThat(run.severity()).isEqualTo("WARN");
        assertThat(reconciliation.findings(f.analyst,f.org,f.store,run.id(),0,10).items())
                .singleElement().satisfies(finding->{
                    assertThat(finding.code()).isEqualTo("POSITION_NOT_INITIALIZED");
                    assertThat(finding.severity()).isEqualTo("WARN");
                    assertThat(finding.positionVersion()).isZero();
                });
    }

    @Test void criticalProjectionMismatchFreezesOnlyItsPositionAndKeepsTheOtherPositionWritable() {
        var damaged=receive(2);
        var unaffected=service.receiveGoods(f.owner,f.org,f.store,receipt(new PositionReference(f.secondLocation,f.product,f.lot),2));
        corruptProjection(damaged.positionId(),"on_hand",3);
        var run=reconciliation.reconcile(f.owner,f.org,f.store);
        assertThat(run.severity()).isEqualTo("CRITICAL");
        var findings=reconciliation.findings(f.analyst,f.org,f.store,run.id(),0,10).items();
        assertThat(findings).filteredOn(finding->finding.severity().equals("CRITICAL")).singleElement()
                .satisfies(finding->assertThat(finding.code()).isEqualTo("PROJECTION_MISMATCH"));
        var frozen=snapshot(damaged);
        assertThat(frozen.trust()).isEqualTo(com.stockrescue.inventory.domain.StockPosition.Trust.FROZEN);
        fails(Kind.POSITION_FROZEN,()->service.recordSale(f.owner,f.org,f.store,change(damaged.positionId(),1)));
        assertThatThrownBy(()->transactions.executeWithoutResult(status->{
            var command=new CommandReceipt(UUID.randomUUID(),f.org,f.store,f.owner,f.ownerMember,Operation.ADJUST,UUID.randomUUID(),new byte[32],"{}");
            inventory.recordCommand(command);var movement=UUID.randomUUID();inventory.recordMovement(movement,command,"Attempt ordinary database write","direct-command");
            inventory.postLine(f.org,f.store,movement,damaged.positionId(),frozen.version(),new StockBuckets.Delta(0,0,0,0),
                    com.stockrescue.inventory.domain.StockPosition.Trust.UNVERIFIED);
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(service.recordSale(f.owner,f.org,f.store,change(unaffected.positionId(),1)).buckets().onHand()).isEqualTo(1);
    }

    @Test void anAuditedProjectionRebuildRestoresAQuarantinedHoldCounterWithoutEditingPriorHistory() {
        var received=receive(4);
        var held=service.hold(f.operator,f.org,f.store,change(received.positionId(),1));
        var originalHistory=service.history(f.analyst,f.org,f.store,received.positionId(),0,100).items();
        corruptProjection(received.positionId(),"other_blocked",0);
        var critical=reconciliation.reconcile(f.owner,f.org,f.store);
        assertThat(critical.severity()).isEqualTo("CRITICAL");
        assertThat(reconciliation.findings(f.analyst,f.org,f.store,critical.id(),0,10).items())
                .anySatisfy(finding->assertThat(finding.code()).isEqualTo("PROJECTION_MISMATCH"));
        var frozen=snapshot(received);
        var request=new InventoryReconciliationService.RepairRequest(UUID.randomUUID(),critical.id(),received.positionId(),
                frozen.version(),"Verified the hold projection against allocation history","inventory-count:hold-review");
        assertThatThrownBy(()->reconciliation.remediateProjection(f.manager,f.org,f.store,request)).isInstanceOf(AccessDeniedException.class);
        var repaired=reconciliation.remediateProjection(f.owner,f.org,f.store,request);
        assertThat(repaired.buckets()).isEqualTo(new StockBuckets(4,0,0,1));
        assertThat(repaired.positionVersion()).isEqualTo(frozen.version()+1);
        long afterFirstRepair=count("stock_movement");
        assertThat(reconciliation.remediateProjection(f.owner,f.org,f.store,request)).isEqualTo(repaired);
        assertThat(count("stock_movement")).isEqualTo(afterFirstRepair);
        var currentHistory=service.history(f.analyst,f.org,f.store,received.positionId(),0,100).items();
        assertThat(currentHistory.subList(0,originalHistory.size())).isEqualTo(originalHistory);
        assertThat(currentHistory).extracting(Movement::operation).contains(Operation.RECONCILE,Operation.REMEDIATE);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.inventory_projection_repair WHERE org_id=:org AND store_id=:store AND position_id=:position")
                .param("org",f.org).param("store",f.store).param("position",received.positionId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND resource_id=:position AND action='INVENTORY_PROJECTION_REBUILT'")
                .param("org",f.org).param("position",received.positionId()).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(()->jdbc.sql("UPDATE stockrescue.inventory_projection_repair SET reason='rewritten' WHERE org_id=:org AND store_id=:store AND position_id=:position")
                .param("org",f.org).param("store",f.store).param("position",received.positionId()).update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("History rows are immutable");
        var clean=reconciliation.reconcile(f.owner,f.org,f.store);
        assertThat(clean.severity()).isEqualTo("CLEAN");
        assertThat(snapshot(received).buckets()).isEqualTo(new StockBuckets(4,0,0,1));
        assertThat(service.releaseHold(f.operator,f.org,f.store,new AllocationChange(change(received.positionId(),1),held.allocationId())).buckets())
                .isEqualTo(new StockBuckets(4,0,0,0));
    }

    @Test void reconciliationWaitsForAnOverlappingStockTransactionAndThenSeesItsCommittedMovement() throws Exception {
        var received=receive(3);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var reconcileStarted=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var writer=workers.submit(()->withCorrelation(()->transactions.execute(status->{
                service.reserveStock(f.operator,f.org,f.store,change(received.positionId(),1));entered.countDown();barrier(release);return true;
            })));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var scanner=workers.submit(()->withCorrelation(()->{reconcileStarted.countDown();return reconciliation.reconcile(f.owner,f.org,f.store);}));
            assertThat(reconcileStarted.await(10,TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(jdbc.sql("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                    """).query(Long.class).single()).isPositive());
            assertThat(scanner.isDone()).isFalse();
            release.countDown();writer.get(20,TimeUnit.SECONDS);
            assertThat(scanner.get(20,TimeUnit.SECONDS).severity()).isEqualTo("CLEAN");
            assertThat(snapshot(received).buckets()).isEqualTo(new StockBuckets(3,1,0,0));
        } finally { release.countDown(); }
    }

    @Test void allStockAuditAndOutboxEffectsRollBackWhenTheCallerFailsBeforeCommit() {
        var received=receive(2); long before=stockAudits();
        assertThatThrownBy(()->transactions.executeWithoutResult(status->{
            service.reserveStock(f.operator,f.org,f.store,change(received.positionId(),1));
            assertThat(count("inventory_outbox_event")).isEqualTo(2);assertThat(count("inventory_outbox_delivery")).isEqualTo(2);
            assertThat(stockAudits()).isEqualTo(before+1);
            throw new IllegalStateException("injected failure after audit and outbox");
        })).isInstanceOf(IllegalStateException.class).hasMessage("injected failure after audit and outbox");
        assertThat(snapshot(received).buckets()).isEqualTo(new StockBuckets(2,0,0,0));
        assertThat(count("stock_allocation")).isZero();assertThat(count("inventory_command_receipt")).isEqualTo(1);
        assertThat(count("stock_movement")).isEqualTo(1);assertThat(count("inventory_outbox_event")).isEqualTo(1);
        assertThat(count("inventory_outbox_delivery")).isEqualTo(1);assertThat(stockAudits()).isEqualTo(before);
    }

    @Test void aZeroRowProjectionCasRollsBackTheNewLedgerLineAndCommand() {
        var received=receive(2);
        assertThatThrownBy(()->transactions.executeWithoutResult(status->{
            jdbc.sql("CREATE FUNCTION stockrescue.test_skip_inventory_projection() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='"+received.positionId()+"'::uuid THEN RETURN NULL; END IF; RETURN NEW; END; $$").update();
            jdbc.sql("CREATE TRIGGER test_skip_inventory_projection BEFORE UPDATE ON stockrescue.stock_position FOR EACH ROW EXECUTE FUNCTION stockrescue.test_skip_inventory_projection()").update();
            service.recordSale(f.owner,f.org,f.store,change(received.positionId(),1));
        })).isInstanceOf(InventoryFailure.class).satisfies(error->assertThat(((InventoryFailure)error).kind()).isEqualTo(Kind.CONFLICT));
        assertThat(snapshot(received).buckets().onHand()).isEqualTo(2);
        assertThat(count("stock_movement")).isEqualTo(1);assertThat(count("inventory_outbox_event")).isEqualTo(1);
    }

    @Test void kafkaAckCrashReplaysTheSameEventWithoutDuplicatingItsInboxOrProjection() {
        var received=receive(3);
        var event=outboxFor(received.positionId(),1);
        var before=snapshot(received);
        double duplicatesBefore=messagingMetrics.duplicateCount();
        String suffix=UUID.randomUUID().toString().replace("-","");
        String sequence="test_publish_failure_"+suffix,function="test_publish_failure_"+suffix,trigger="test_publish_failure_"+suffix;
        jdbc.sql("CREATE SEQUENCE stockrescue."+sequence).update();
        jdbc.sql("CREATE FUNCTION stockrescue."+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                +"IF NEW.event_id='"+event.id()+"'::uuid AND NEW.published_at IS NOT NULL "
                +"AND nextval('stockrescue."+sequence+"')=1 THEN RAISE EXCEPTION 'injected failure after broker acknowledgement'; END IF; "
                +"RETURN NEW; END; $$").update();
        jdbc.sql("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON stockrescue.inventory_outbox_delivery FOR EACH ROW EXECUTE FUNCTION stockrescue."+function+"()").update();
        try {
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
                relay.dispatchOnce();
                assertThat(inboxCount(event.id())).isEqualTo(1);
            });
            assertThat(publishedAt(event.id())).isNull();
            assertThat(snapshot(received)).isEqualTo(before);
            assertThat(jdbc.sql("UPDATE stockrescue.inventory_outbox_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id=:event")
                    .param("event",event.id()).update()).isEqualTo(1);
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
                relay.dispatchOnce();
                assertThat(publishedAt(event.id())).isNotNull();
            });
        } finally {
            jdbc.sql("DROP TRIGGER "+trigger+" ON stockrescue.inventory_outbox_delivery").update();
            jdbc.sql("DROP FUNCTION stockrescue."+function+"()").update();
            jdbc.sql("DROP SEQUENCE stockrescue."+sequence).update();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(messagingMetrics.duplicateCount()).isGreaterThan(duplicatesBefore));
        assertThat(inboxCount(event.id())).isEqualTo(1);
        assertThat(feedCount(event.id())).isEqualTo(1);
        assertThat(projectionVersion(received.positionId())).isEqualTo(1);
        assertThat(snapshot(received)).isEqualTo(before);
    }

    @Test void consumerCommitsItsProjectionBeforeAcknowledgingAndReplayIsIdempotent() {
        var received=receive(2);
        var source=outboxFor(received.positionId(),1);
        var event=eventCodec.fromOutbox(source);
        var key=event.tenantId()+":"+event.aggregateType()+":"+event.aggregateId();
        double duplicatesBefore=messagingMetrics.duplicateCount();
        AtomicBoolean crashOnce=new AtomicBoolean(true);
        var record=new ConsumerRecord<>("stockrescue.inventory.events.v1",0,8_000_000_000L,key,eventCodec.encode(event));
        var acknowledgment=org.mockito.Mockito.mock(Acknowledgment.class);
        org.mockito.Mockito.doAnswer(invocation->{
            if(crashOnce.compareAndSet(true,false)) throw new IllegalStateException("simulated failure before offset acknowledgement");
            return null;
        }).when(acknowledgment).acknowledge();
        assertThatThrownBy(()->changeListener.consume(record,acknowledgment)).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verify(acknowledgment).acknowledge();
        assertThat(inboxCount(source.id())).isEqualTo(1);
        assertThat(feedCount(source.id())).isEqualTo(1);
        changeListener.consume(record,acknowledgment);
        assertThat(messagingMetrics.duplicateCount()).isGreaterThan(duplicatesBefore);
        assertThat(inboxCount(source.id())).isEqualTo(1);
        assertThat(feedCount(source.id())).isEqualTo(1);
        assertThat(projectionVersion(received.positionId())).isEqualTo(1);
    }

    @Test void lowerAggregateVersionCannotReplaceAnAlreadyAppliedNewerSnapshot() {
        var received=receive(3);
        var versionOne=outboxFor(received.positionId(),1);
        var sale=service.recordSale(f.owner,f.org,f.store,change(received.positionId(),1));
        var versionTwo=outboxFor(received.positionId(),2);
        var newer=eventCodec.fromOutbox(versionTwo);
        var older=eventCodec.fromOutbox(versionOne);
        var key=newer.tenantId()+":"+newer.aggregateType()+":"+newer.aggregateId();
        assertThat(changeProcessor.process(key,eventCodec.encode(newer))).isEqualTo(InventoryChangeProcessor.Outcome.APPLIED);
        assertThat(changeProcessor.process(key,eventCodec.encode(older))).isEqualTo(InventoryChangeProcessor.Outcome.STALE);
        assertThat(projectionVersion(received.positionId())).isEqualTo(2);
        assertThat(jdbc.sql("SELECT processing_outcome FROM stockrescue.inventory_inbox WHERE event_id=:event")
                .param("event",versionOne.id()).query(String.class).single()).isEqualTo("STALE");
        assertThat(snapshot(received).buckets()).isEqualTo(sale.buckets());
        assertThat(snapshot(received).version()).isEqualTo(sale.positionVersion());
    }

    @Test void spoofedTenantMalformedAndUnsupportedRecordsAreParkedBeforeTheirOffsetsAreAcknowledged() {
        var received=receive(2);
        var source=outboxFor(received.positionId(),1);
        var trusted=eventCodec.fromOutbox(source);
        var spoofed=new InventoryStockChangedEvent(trusted.eventId(),trusted.eventType(),trusted.schemaVersion(),trusted.occurredAt(),
                f.otherOrg,trusted.aggregateType(),trusted.aggregateId(),trusted.aggregateVersion(),trusted.correlationId(),
                trusted.causationId(),trusted.dataClassification(),trusted.data());
        var key=spoofed.tenantId()+":"+spoofed.aggregateType()+":"+spoofed.aggregateId();
        var unsupported=eventCodec.encode(trusted).replaceFirst("\"schemaVersion\"\\s*:\\s*1","\"schemaVersion\":2");
        var records=List.of(
                new ConsumerRecord<>("stockrescue.inventory.events.v1",0,8_000_000_001L,key,eventCodec.encode(spoofed)),
                new ConsumerRecord<>("stockrescue.inventory.events.v1",0,8_000_000_002L,"invalid",unsupported),
                new ConsumerRecord<>("stockrescue.inventory.events.v1",0,8_000_000_003L,"invalid","not-json"));
        MessageListenerContainer container=listeners.getListenerContainer("inventory-change-projection");
        container.stop();
        try {
            for(var record:records) {
                var acknowledgment=org.mockito.Mockito.mock(Acknowledgment.class);
                changeListener.consume(record,acknowledgment);
                org.mockito.Mockito.verify(acknowledgment).acknowledge();
            }
        } finally {
            container.start();
            await().atMost(Duration.ofSeconds(10)).until(container::isRunning);
        }
        assertThat(jdbc.sql("SELECT failure_code FROM stockrescue.inventory_consumer_parked_event WHERE record_offset>=8000000001 ORDER BY record_offset")
                .query(String.class).list()).containsExactly("OUTBOX_EVENT_MISMATCH","UNSUPPORTED_SCHEMA_VERSION","MALFORMED_EVENT");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.inventory_consumer_parked_event WHERE record_offset>=8000000001 AND octet_length(payload_digest)=32")
                .query(Long.class).single()).isEqualTo(3);
        assertThat(inboxCount(source.id())).isZero();
        assertThat(feedCount(source.id())).isZero();
        assertThat(snapshot(received).buckets().onHand()).isEqualTo(2);
    }

    @Test void brokerFailureLeavesCommittedStockAndOutboxAvailableForLaterDelivery() {
        var received=receive(2);
        var source=outboxFor(received.positionId(),1);
        assertThat(count("stock_movement")).isEqualTo(1);
        AtomicBoolean failOnce=new AtomicBoolean(true);
        doAnswer(invocation->{
            @SuppressWarnings("unchecked") var record=(ProducerRecord<String,String>)invocation.getArgument(0);
            if(record.value().contains(source.id().toString())&&failOnce.compareAndSet(true,false))
                return java.util.concurrent.CompletableFuture.failedFuture(new org.springframework.kafka.KafkaException("simulated broker outage"));
            return invocation.callRealMethod();
        }).when(kafka).send(org.mockito.ArgumentMatchers.any(ProducerRecord.class));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
            relay.dispatchOnce();
            assertThat(outboxAttempts(source.id())).isPositive();
        });
        assertThat(publishedAt(source.id())).isNull();
        assertThat(snapshot(received).buckets().onHand()).isEqualTo(2);
        assertThat(lastDeliveryError(source.id())).isEqualTo("BROKER_UNAVAILABLE");
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
            relay.dispatchOnce();
            assertThat(publishedAt(source.id())).isNotNull();
        });
    }

    @Test void committedInventoryRemainsPendingDuringKafkaOutageAndDrainsAfterRecovery() {
        boolean paused=false;
        Result received;
        InventoryOutboxRepository.OutboxEvent event;
        try {
            KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
            paused=true;
            received=receive(2);
            event=outboxFor(received.positionId(),1);
            assertThat(publishedAt(event.id())).isNull();
            relay.dispatchOnce();
            assertThat(publishedAt(event.id())).isNull();
            assertThat(outboxAttempts(event.id())).isPositive();
            assertThat(snapshot(received).buckets().onHand()).isEqualTo(2);
        } finally {
            if(paused) KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(()->{
            relay.dispatchOnce();
            assertThat(publishedAt(event.id())).isNotNull();
            assertThat(inboxCount(event.id())).isEqualTo(1);
            assertThat(projectionVersion(received.positionId())).isEqualTo(1);
        });
        assertThat(feedCount(event.id())).isEqualTo(1);
        assertThat(snapshot(received).buckets().onHand()).isEqualTo(2);
    }

    @Test void inventoryConsumerContinuesProcessingAfterItsGroupRebalances() {
        var container=listeners.getListenerContainer("inventory-change-projection");
        assertThat(container).isNotNull();
        container.stop();
        assertThat(container.isRunning()).isFalse();
        container.start();
        await().atMost(Duration.ofSeconds(15)).until(container::isRunning);

        var received=receive(2);
        var event=outboxFor(received.positionId(),1);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
            relay.dispatchOnce();
            assertThat(publishedAt(event.id())).isNotNull();
            assertThat(inboxCount(event.id())).isEqualTo(1);
            assertThat(projectionVersion(received.positionId())).isEqualTo(1);
        });
        assertThat(feedCount(event.id())).isEqualTo(1);
        assertThat(snapshot(received).buckets().onHand()).isEqualTo(2);
    }

    @Test void expiredRelayLeaseFencesTheOldOwnerFromPublishingRetryingOrParking() {
        receive(1);
        var firstOwner=UUID.randomUUID();
        var nextOwner=UUID.randomUUID();
        var stale=outbox.claimDue(firstOwner,1,Duration.ofSeconds(20)).getFirst();
        assertThat(stale.leaseOwner()).isEqualTo(firstOwner);
        assertThat(jdbc.sql("UPDATE stockrescue.inventory_outbox_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id=:event")
                .param("event",stale.id()).update()).isEqualTo(1);
        var current=outbox.claimDue(nextOwner,1,Duration.ofSeconds(20)).getFirst();
        assertThat(current.id()).isEqualTo(stale.id());
        assertThat(current.claimVersion()).isEqualTo(stale.claimVersion()+1);
        assertThat(outbox.markPublished(stale)).isFalse();
        assertThat(outbox.scheduleRetry(stale,"BROKER_UNAVAILABLE",Duration.ofSeconds(1))).isFalse();
        assertThat(outbox.park(stale,"OUTBOX_CONTRACT_INVALID")).isFalse();
        assertThat(outbox.scheduleRetry(current,"BROKER_UNAVAILABLE",Duration.ofMillis(1))).isTrue();
    }

    @Test void relayClaimingCannotReorderVersionsOfTheSamePosition() {
        var received=receive(3);
        var versionOne=outboxFor(received.positionId(),1);
        service.recordSale(f.owner,f.org,f.store,change(received.positionId(),1));
        var versionTwo=outboxFor(received.positionId(),2);
        var owner=UUID.randomUUID();
        var laterClaim=new AtomicReference<InventoryOutboxRepository.OutboxEvent>();
        var reordered=new AtomicBoolean();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).until(()->{
            var claims=outbox.claimDue(owner,5,Duration.ofSeconds(30));
            boolean first=claims.stream().anyMatch(claim->claim.id().equals(versionOne.id()));
            boolean second=claims.stream().anyMatch(claim->claim.id().equals(versionTwo.id()));
            if(first) {
                if(second) reordered.set(true);
                claims.stream().filter(claim->claim.id().equals(versionOne.id())).findFirst()
                        .ifPresent(claim->assertThat(outbox.markPublished(claim)).isTrue());
                claims.stream().filter(claim->!claim.id().equals(versionOne.id()))
                        .forEach(claim->outbox.scheduleRetry(claim,"BROKER_UNAVAILABLE",Duration.ofMillis(1)));
            } else {
                claims.forEach(claim->outbox.scheduleRetry(claim,"BROKER_UNAVAILABLE",Duration.ofMillis(1)));
            }
            return first;
        });
        assertThat(reordered).isFalse();
        var nextOwner=UUID.randomUUID();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).until(()->{
            var claims=outbox.claimDue(nextOwner,5,Duration.ofSeconds(30));
            var later=claims.stream().filter(claim->claim.id().equals(versionTwo.id())).findFirst().orElse(null);
            if(later!=null) laterClaim.set(later);
            claims.forEach(claim->outbox.scheduleRetry(claim,"BROKER_UNAVAILABLE",Duration.ofMillis(1)));
            return later!=null;
        });
        assertThat(laterClaim.get().aggregateVersion()).isEqualTo(versionOne.aggregateVersion()+1);
    }

    @Test void eventRelayDeliversTheCommittedStockChangeToTheDerivedConsumerProjection() {
        var received=receive(4);
        var event=outboxFor(received.positionId(),1);
        var before=snapshot(received);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->{
            relay.dispatchOnce();
            assertThat(publishedAt(event.id())).isNotNull();
            assertThat(inboxCount(event.id())).isEqualTo(1);
            assertThat(projectionVersion(received.positionId())).isEqualTo(1);
        });
        assertThat(feedCount(event.id())).isEqualTo(1);
        assertThat(snapshot(received)).isEqualTo(before);
        assertThat(jdbc.sql("SELECT change->>'unit' FROM stockrescue.inventory_change_feed WHERE event_id=:event")
                .param("event",event.id()).query(String.class).single()).isEqualTo("EA");
    }

    @Test void aCommittedGrantRevocationRejectsAWaitingCommandAndItsLaterReplay() throws Exception {
        var received=receive(2); var request=change(received.positionId(),1);
        service.recordSale(f.operator,f.org,f.store,request);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            try {
                var revoked=workers.submit(()->withCorrelation(()->transactions.execute(status->{
                    staff.revokeGrant(f.owner,f.org,f.operatorMember,f.store,0,0,"Assignment ended");
                    entered.countDown();barrier(release);return true;
                })));
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                var waiting=workers.submit(()->withCorrelation(()->{
                    assertThatThrownBy(()->service.recordSale(f.operator,f.org,f.store,request)).isInstanceOf(OrganizationUnavailableException.class);return true;
                }));
                awaitLock("organization");release.countDown();assertThat(revoked.get(15,TimeUnit.SECONDS)).isTrue();assertThat(waiting.get(15,TimeUnit.SECONDS)).isTrue();
            } finally { release.countDown(); }
        }
        assertThatThrownBy(()->service.recordSale(f.operator,f.org,f.store,request)).isInstanceOf(OrganizationUnavailableException.class);
        assertThat(snapshot(received).buckets().onHand()).isEqualTo(1);assertThat(count("stock_movement")).isEqualTo(2);
    }

    @Test void aRecallCommittedWhileSaleWaitsInvalidatesTheEarlierAllowedReview() throws Exception {
        var received=receive(2);
        assertThat(safety.evaluate(f.owner,f.org,f.lot,f.store,null,f.location,FoodSafetyEvaluation.Operation.SALE).decision()).isEqualTo(FoodSafetyEvaluation.Decision.ALLOWED);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            try {
                var recalled=workers.submit(()->withCorrelation(()->transactions.execute(status->{ recordRecall();entered.countDown();barrier(release);return true; })));
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                var waiting=workers.submit(()->attempt(()->service.recordSale(f.operator,f.org,f.store,change(received.positionId(),1))));
                awaitLock("manufacturer_lot"); release.countDown();assertThat(recalled.get(15,TimeUnit.SECONDS)).isTrue();
                assertThat(waiting.get(15,TimeUnit.SECONDS).failure).isEqualTo(Kind.LOT_NOT_ELIGIBLE);
            } finally { release.countDown(); }
        }
        assertThat(snapshot(received).buckets().onHand()).isEqualTo(2);assertThat(count("stock_movement")).isEqualTo(1);
    }

    @Test void storeRolesEnforceWriteOffAdjustmentAndReadBoundaries() {
        var received=receive(4);
        assertThat(service.snapshot(f.analyst,f.org,f.store,received.positionId()).buckets().onHand()).isEqualTo(4);
        assertThatThrownBy(()->service.reserveStock(f.analyst,f.org,f.store,change(received.positionId(),1))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.adjustStock(f.operator,f.org,f.store,change(received.positionId(),1))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.writeOff(f.operator,f.org,f.store,new AllocationChange(change(received.positionId(),1),null))).isInstanceOf(AccessDeniedException.class);
        service.writeOff(f.manager,f.org,f.store,new AllocationChange(change(received.positionId(),1),null));
        assertThat(snapshot(received).buckets().onHand()).isEqualTo(3);
    }

    @Test void replayRechecksManagerAuthorityOverAnotherOperatorsReservation() {
        var received=receive(2);
        var reserved=service.reserveStock(f.operator,f.org,f.store,change(received.positionId(),1));
        var release=new AllocationChange(change(received.positionId(),1),reserved.allocationId());
        service.releaseReservation(f.manager,f.org,f.store,release);
        var membership=jdbc.sql("SELECT id FROM stockrescue.membership WHERE org_id=:org AND user_id=:user")
                .param("org",f.org).param("user",f.manager).query(UUID.class).single();
        staff.grant(f.owner,f.org,membership,f.store,com.stockrescue.access.application.EffectivePermissions.StoreRole.INVENTORY_OPERATOR,0,0L,"Assignment now covers routine operations");
        assertThatThrownBy(()->service.releaseReservation(f.manager,f.org,f.store,release)).isInstanceOf(AccessDeniedException.class);
        assertThat(count("stock_movement")).isEqualTo(3);
    }

    private Result receive(long quantity) { return service.receiveGoods(f.owner,f.org,f.store,receipt(f.reference(),quantity)); }
    private InventoryOutboxRepository.OutboxEvent outboxFor(UUID position,long version) {
        UUID id=jdbc.sql("SELECT id FROM stockrescue.inventory_outbox_event WHERE org_id=:org AND position_id=:position AND aggregate_version=:version")
                .param("org",f.org).param("position",position).param("version",version).query(UUID.class).single();
        return outbox.find(id);
    }
    private long inboxCount(UUID event) {
        return jdbc.sql("SELECT count(*) FROM stockrescue.inventory_inbox WHERE event_id=:event").param("event",event).query(Long.class).single();
    }
    private long feedCount(UUID event) {
        return jdbc.sql("SELECT count(*) FROM stockrescue.inventory_change_feed WHERE event_id=:event").param("event",event).query(Long.class).single();
    }
    private long projectionVersion(UUID position) {
        return jdbc.sql("SELECT aggregate_version FROM stockrescue.inventory_change_projection WHERE consumer_name='inventory-change-projection-v1' AND org_id=:org AND store_id=:store AND position_id=:position")
                .param("org",f.org).param("store",f.store).param("position",position).query(Long.class).single();
    }
    private java.time.Instant publishedAt(UUID event) {
        return jdbc.sql("SELECT published_at FROM stockrescue.inventory_outbox_delivery WHERE event_id=:event")
                .param("event",event).query((row,index)->row.getTimestamp(1)==null?null:row.getTimestamp(1).toInstant())
                .optional().orElse(null);
    }
    private int outboxAttempts(UUID event) {
        return jdbc.sql("SELECT attempt_count FROM stockrescue.inventory_outbox_delivery WHERE event_id=:event")
                .param("event",event).query(Integer.class).single();
    }
    private String lastDeliveryError(UUID event) {
        return jdbc.sql("SELECT last_error_code FROM stockrescue.inventory_outbox_delivery WHERE event_id=:event")
                .param("event",event).query(String.class).single();
    }
    private ReceiveGoods receipt(PositionReference reference,long quantity) {
        return new ReceiveGoods(UUID.randomUUID(),reference,quantity,"supplier:registered-1","DELIVERY",UUID.randomUUID().toString(),"1",NOW.minusSeconds(3600),"Counted physical delivery","signed-delivery-note");
    }
    private StockChange change(UUID position,long quantity) { return new StockChange(UUID.randomUUID(),position,null,quantity,"Observed stock operation","operator-record"); }
    private com.stockrescue.inventory.domain.StockPosition snapshot(Result result) { return service.snapshot(f.owner,f.org,f.store,result.positionId()); }
    private void recordRecall() {
        long version=jdbc.sql("SELECT version FROM stockrescue.manufacturer_lot WHERE id=:id").param("id",f.lot).query(Long.class).single();
        lots.recordSafetyEvidence(f.owner,f.org,f.lot,UUID.randomUUID(),new LotSafetyEvidence(version,"Manufacturer recall notice",ManufacturerLot.RecallState.RECALLED,
                ManufacturerLot.DamageState.NOT_REPORTED,new EvidenceProvenance("Manufacturer notice","recall-document")));
    }
    private void reconstruct(Result result) {
        var position=snapshot(result);
        var totals=jdbc.sql("SELECT coalesce(sum(delta_on_hand),0)::bigint AS physical,coalesce(sum(delta_reserved),0)::bigint AS reserved,coalesce(sum(delta_quarantined),0)::bigint AS quarantine,coalesce(sum(delta_other_blocked),0)::bigint AS blocked FROM stockrescue.stock_movement_line WHERE org_id=:org AND store_id=:store AND position_id=:position")
                .param("org",f.org).param("store",f.store).param("position",result.positionId()).query((row,index)->new StockBuckets(row.getLong("physical"),row.getLong("reserved"),row.getLong("quarantine"),row.getLong("blocked"))).single();
        assertThat(totals).isEqualTo(position.buckets());
        assertThat(count("stock_movement_line")).isEqualTo(position.version());
        assertThat(jdbc.sql("SELECT coalesce(sum(remaining_quantity),0)::bigint FROM stockrescue.stock_allocation WHERE org_id=:org AND store_id=:store AND position_id=:position")
                .param("org",f.org).param("store",f.store).param("position",result.positionId()).query(Long.class).single())
                .isEqualTo(totals.reserved()+totals.quarantined()+totals.otherBlocked());
    }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM stockrescue."+table+" WHERE org_id=:org").param("org",f.org).query(Long.class).single(); }
    private long stockAudits() { return jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action LIKE 'STOCK_%'").param("org",f.org).query(Long.class).single(); }
    private void corruptProjection(UUID position,String column,long value) {
        assertThat(List.of("on_hand","reserved","quarantined","other_blocked")).contains(column);
        transactions.executeWithoutResult(status->{
            jdbc.sql("ALTER TABLE stockrescue.stock_position DISABLE TRIGGER stock_position_guard").update();
            try {
                jdbc.sql("UPDATE stockrescue.stock_position SET "+column+"=:value WHERE org_id=:org AND store_id=:store AND id=:position")
                        .param("value",value).param("org",f.org).param("store",f.store).param("position",position).update();
            } finally {
                jdbc.sql("ALTER TABLE stockrescue.stock_position ENABLE TRIGGER stock_position_guard").update();
            }
        });
    }
    private void sql(String query,Object... values) { var statement=jdbc.sql(query);for(int index=0;index<values.length;index++) statement.param(index+1,values[index]);statement.update(); }
    private void fails(Kind kind,Callable<?> work) { assertThatThrownBy(work::call).isInstanceOfSatisfying(InventoryFailure.class,error->assertThat(error.kind()).isEqualTo(kind)); }
    private Attempt attempt(Callable<Result> work) throws Exception {
        return withCorrelation(()->{ try { return new Attempt(work.call(),null); } catch(InventoryFailure failure) { return new Attempt(null,failure.kind()); } });
    }
    private static <T> T withCorrelation(Callable<T> work) throws Exception { try(var ignored=MDC.putCloseable("correlationId",UUID.randomUUID().toString())) { return work.call(); } }
    private List<Attempt> race(Callable<Result> first,Callable<Result> second) throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var one=workers.submit(()->{ready.countDown();barrier(start);return attempt(first);});
            var two=workers.submit(()->{ready.countDown();barrier(start);return attempt(second);});
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();return List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));
        } finally { start.countDown(); }
    }
    private static void barrier(CountDownLatch latch) {
        try { if(!latch.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Transaction barrier timed out"); }
        catch(InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException(error); }
    }
    private void awaitLock(String table) {
        await().atMost(Duration.ofSeconds(8)).untilAsserted(()->assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE :table")
                .param("table","%stockrescue."+table+"%").query(Long.class).single()).isPositive());
    }
    private record Attempt(Result result,Kind failure) {}

    private class Fixture {
        final UUID owner=user(),operator=user(),manager=user(),analyst=user(),peer=user(),foreign=user();
        final UUID org=UUID.randomUUID(),otherOrg=UUID.randomUUID(),store=UUID.randomUUID(),otherStore=UUID.randomUUID();
        final UUID ownerMember=UUID.randomUUID(),operatorMember=UUID.randomUUID(),zone=UUID.randomUUID(),otherZone=UUID.randomUUID();
        final UUID location=UUID.randomUUID(),otherLocation=UUID.randomUUID(),secondLocation=UUID.randomUUID(),product=UUID.randomUUID(),lot=UUID.randomUUID();
        Fixture() {
            sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (?,'Inventory retailer',?),(?,'Foreign retailer',?)",org,owner,otherOrg,foreign);
            sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (?,?,?,'ORG_ADMIN'),(?,?,?,'MEMBER'),(?,?,?,'ORG_ADMIN')",ownerMember,org,owner,operatorMember,org,operator,UUID.randomUUID(),otherOrg,foreign);
            sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (?,?,'NORTH','North','UTC'),(?,?,'SOUTH','South','UTC')",store,org,otherStore,org);
            grant(operatorMember,store,"INVENTORY_OPERATOR");member(manager,"STORE_MANAGER");member(analyst,"READ_ONLY_ANALYST");member(peer,"INVENTORY_OPERATOR");
            sql("INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES (?,?,?,'Dry','AMBIENT'),(?,?,?,'Dry','AMBIENT')",zone,org,store,otherZone,org,otherStore);
            sql("INSERT INTO stockrescue.storage_location(id,org_id,store_id,zone_id,code,name) VALUES (?,?,?,?,'A-1','Dry shelf'),(?,?,?,?,'A-1','Dry shelf'),(?,?,?,?,'A-2','Dry shelf')",location,org,store,zone,otherLocation,org,otherStore,otherZone,secondLocation,org,store,zone);
            sql("INSERT INTO stockrescue.product(id,org_id,sku,name,base_unit,temperature_class,category) VALUES (?,?,'BREAD','Packaged bread','EA','AMBIENT','BAKERY')",product,org);
            classification.confirm(owner,org,product,new FoodSafetyProductClassificationConfirmationSubmission(UUID.randomUUID(),0,ProductCategory.BAKERY,TemperatureClass.AMBIENT,
                    ProductProfile.PACKAGED_SHELF_STABLE_DRY_BAKERY,SourceType.ORIGINAL_PACKAGE,"package:label","Reviewed original package"));
            sql("""
                    INSERT INTO stockrescue.manufacturer_lot(id,org_id,product_id,lot_code,identity_state,manufacturer_identity_key,manufacturer_source,
                        manufacturer_reference,confirmed_by_membership_id,confirmed_at,labeled_value,date_marking_type,date_precision,marking_source,marking_reference,
                        recall_state,damage_state,status_source,status_reference)
                    VALUES (?,?,?,'BREAD-LOT','CONFIRMED','maker:registered-1','Manufacturer register','register-record',?,clock_timestamp(),
                        '2027-04-30','USE_BY','DAY','Original package','label-photo','NOT_REPORTED','NOT_REPORTED','Manufacturer notice','notice-record')
                    """,lot,org,product,ownerMember);
            assertThat(safety.evaluate(owner,org,lot,store,null,location,FoodSafetyEvaluation.Operation.SALE).decision()).isEqualTo(FoodSafetyEvaluation.Decision.ALLOWED);
        }
        UUID user() { var id=UUID.randomUUID();sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (?,?)",id,id+"@example.test");return id; }
        void grant(UUID member,UUID targetStore,String role) { sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (?,?,?,?,?)",UUID.randomUUID(),org,member,targetStore,role); }
        void member(UUID user,String role) { var id=UUID.randomUUID();sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (?,?,?,'MEMBER')",id,org,user);grant(id,store,role); }
        PositionReference reference() { return new PositionReference(location,product,lot); }
    }
    @TestConfiguration static class TimeConfiguration { @Bean @Primary TestClock inventoryTestClock() { return new TestClock(); } }
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> value=new AtomicReference<>(NOW);
        void set(Instant instant) { value.set(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
        @Override public Instant instant() { return value.get(); }
    }
}
