package com.stockrescue.inventory.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.stockrescue.inventory.domain.InventoryWriteConflict;
import com.stockrescue.inventory.domain.StockBuckets;
import com.stockrescue.inventory.domain.StockBuckets.Delta;
import com.stockrescue.inventory.domain.StockPosition;
import com.stockrescue.inventory.domain.StockPosition.Initialization;
import com.stockrescue.inventory.domain.StockPosition.Trust;
import com.stockrescue.inventory.persistence.InventoryRepository.CommandReceipt;
import com.stockrescue.inventory.persistence.InventoryRepository.Operation;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.support.MigrationAssertions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class InventoryPersistenceTest {
    private static final PostgreSQLContainer DATABASE=database();
    private static DriverManagerDataSource source;
    private static JdbcClient jdbc;
    private static TransactionTemplate transactions;
    private static InventoryRepository repository;

    @BeforeAll static void start() {
        DATABASE.start();
        source=dataSource(DATABASE);
        jdbc=JdbcClient.create(source);
        transactions=new TransactionTemplate(new JdbcTransactionManager(source));
        repository=new InventoryRepository(jdbc,new ObjectMapper());
        migrations(source).migrate();
    }
    @AfterAll static void stop() { DATABASE.stop(); }

    @Test void freshDatabaseValidatesAndRestartsWithTheInventorySchema() {
        var flyway=migrations(source);
        flyway.validate();
        MigrationAssertions.assertCurrent(flyway);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='stockrescue'").query(Integer.class).single()).isEqualTo(49);
    }

    @Test void currentCatalogDatabaseUpgradesWithoutChangingExistingHistoryOrEvidence() throws Exception {
        try (var previous=database()) {
            previous.start();
            var previousSource=dataSource(previous);
            var baseline=Flyway.configure().dataSource(previousSource).defaultSchema("public").target("11").cleanDisabled(true).load();
            assertThat(baseline.migrate().migrationsExecuted).isEqualTo(11);
            var previousJdbc=JdbcClient.create(previousSource);
            String history=previousJdbc.sql("SELECT json_agg(h ORDER BY installed_rank)::text FROM public.flyway_schema_history h").query(String.class).single();
            UUID product=UUID.randomUUID(), user=UUID.randomUUID(), org=UUID.randomUUID();
            previousJdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)").param("id",user).param("email",user+"@example.test").update();
            previousJdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Existing retailer',:user)").param("id",org).param("user",user).update();
            previousJdbc.sql("INSERT INTO stockrescue.product(id,org_id,sku,name,base_unit,temperature_class) VALUES (:id,:org,'EXISTING','Retained goods','EA','AMBIENT')")
                    .param("id",product).param("org",org).update();
            assertThat(migrations(previousSource).migrate().migrationsExecuted).isEqualTo(3);
            UUID member=UUID.randomUUID(),store=UUID.randomUUID(),run=UUID.randomUUID();
            previousJdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:user,'ORG_ADMIN')")
                    .param("id",member).param("org",org).param("user",user).update();
            previousJdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:id,:org,'NORTH','North','UTC')")
                    .param("id",store).param("org",org).update();
            previousJdbc.sql("""
                    INSERT INTO stockrescue.inventory_reconciliation_run(id,org_id,store_id,actor_user_id,membership_id,severity,
                        checked_positions,started_at,completed_at)
                    VALUES (:id,:org,:store,:user,:member,'CLEAN',0,:started,:completed)
                    """).param("id",run).param("org",org).param("store",store).param("user",user).param("member",member)
                    .param("started",java.sql.Timestamp.from(java.time.Instant.parse("2026-11-12T10:00:00Z")))
                    .param("completed",java.sql.Timestamp.from(java.time.Instant.parse("2026-11-12T10:00:01Z"))).update();
            previous.getDockerClient().restartContainerCmd(previous.getContainerId()).exec();
            await().atMost(java.time.Duration.ofSeconds(30)).ignoreExceptions()
                    .untilAsserted(()->migrations(dataSource(previous)).validate());
            previousSource=dataSource(previous);
            var restarted=migrations(previousSource);
            restarted.validate();
            MigrationAssertions.assertCurrent(restarted);
            assertThat(restarted.migrate().migrationsExecuted).isZero();
            previousJdbc=JdbcClient.create(previousSource);
            assertThat(previousJdbc.sql("SELECT name FROM stockrescue.product WHERE id=:id").param("id",product).query(String.class).single()).isEqualTo("Retained goods");
            assertThat(previousJdbc.sql("SELECT severity FROM stockrescue.inventory_reconciliation_run WHERE id=:id").param("id",run).query(String.class).single()).isEqualTo("CLEAN");
            assertThat(previousJdbc.sql("SELECT json_agg(h ORDER BY installed_rank)::text FROM public.flyway_schema_history h WHERE version::integer<=11").query(String.class).single()).isEqualTo(history);
        }
    }

    @Test void positionsEnforceTenantStoreLocationAndProductLotIdentity() {
        within(f->{
            assertThat(repository.position(f.org,f.store,f.position,false).orElseThrow().initialization()).isEqualTo(Initialization.NOT_INITIALIZED);
            assertThat(repository.position(f.otherOrg,f.otherStore,f.position,false)).isEmpty();
            reject("23505",()->insertPosition(UUID.randomUUID(),f.org,f.store,f.location,f.product,f.lot));
            reject("23503",()->insertPosition(UUID.randomUUID(),f.otherOrg,f.otherStore,f.location,f.product,f.lot));
            reject("23503",()->insertPosition(UUID.randomUUID(),f.org,f.otherStore,f.location,f.product,f.lot));
            var product=UUID.randomUUID();
            sql("INSERT INTO stockrescue.product(id,org_id,sku,name,base_unit,temperature_class) VALUES (?,?,'OTHER','Other','EA','AMBIENT')",product,f.org);
            var location=UUID.randomUUID();
            sql("INSERT INTO stockrescue.storage_location(id,org_id,store_id,zone_id,code,name) VALUES (?,?,?,?,'A-2','Aisle two')",location,f.org,f.store,f.zone);
            reject("23503",()->insertPosition(UUID.randomUUID(),f.org,f.store,location,product,f.lot));
            reject("23514",()->sql("UPDATE stockrescue.stock_position SET location_id=?,version=version+1 WHERE id=?",UUID.randomUUID(),f.position));
            reject("23502",()->sql("INSERT INTO stockrescue.stock_position(id,org_id,store_id,location_id,product_id,lot_id) VALUES (?,?,?,NULL,?,?)",UUID.randomUUID(),f.org,f.store,f.product,f.lot));
        });
    }

    @Test void bucketsRejectOverdrawUnsupportedUnitsAndOverflowWithoutAQuantitySetter() {
        within(f->{
            var first=post(f,Operation.RECEIVE,new Delta(Long.MAX_VALUE,0,0,0));
            assertThat(first.position.buckets().available()).isEqualTo(Long.MAX_VALUE);
            rejectJava(ArithmeticException.class,()->post(f,Operation.RECEIVE,new Delta(1,0,0,0)));
            rejectJava(IllegalArgumentException.class,()->post(f,Operation.RESERVE,new Delta(0,Long.MAX_VALUE,1,0)));
            reject("23514",()->sql("UPDATE stockrescue.stock_position SET on_hand=0,version=version+1 WHERE id=?",f.position));
            reject("23514",()->sql("UPDATE stockrescue.stock_position SET version=version+2 WHERE id=?",f.position));
            reject("23514",()->sql("INSERT INTO stockrescue.stock_position(id,org_id,store_id,location_id,product_id,lot_id,unit) VALUES (?,?,?,?,?,?,'KG')",UUID.randomUUID(),f.org,f.store,f.location,f.product,f.lot));
            reject("22003",()->sql("INSERT INTO stockrescue.stock_movement_line(org_id,store_id,movement_id,position_id,position_version,delta_on_hand,delta_reserved,delta_quarantined,delta_other_blocked,initialization_after,trust_after) VALUES (?,?,?,?,2,9223372036854775808,0,0,0,'INITIALIZED','UNVERIFIED')",f.org,f.store,first.movement,f.position));
            reject("23514",()->sql("UPDATE stockrescue.stock_position SET on_hand=-1,version=version+1 WHERE id=?",f.position));
            constraints();
        });
    }

    @Test void physicalAndDisjointBucketHistoryReconstructsAfterPartialReleaseAndBlockedWriteOff() {
        within(f->{
            post(f,Operation.RECEIVE,new Delta(10,0,0,0));
            var reservation=post(f,Operation.RESERVE,new Delta(0,4,0,0));
            var reserved=openAllocation(f,reservation,"RESERVED",4);
            var quarantine=post(f,Operation.QUARANTINE,new Delta(0,0,3,0));
            openAllocation(f,quarantine,"QUARANTINED",3);
            var hold=post(f,Operation.HOLD,new Delta(0,0,0,1));
            var held=openAllocation(f,hold,"OTHER_BLOCKED",1);
            var release=post(f,Operation.RELEASE,new Delta(0,-2,0,0));
            transition(f,release,reserved,"RESERVED",1,4,2,"ACTIVE","RELEASE");
            var writeOff=post(f,Operation.WRITE_OFF,new Delta(-1,0,0,-1));
            transition(f,writeOff,held,"OTHER_BLOCKED",1,1,0,"WRITTEN_OFF","WRITE_OFF");
            var sale=post(f,Operation.SALE,new Delta(-2,0,0,0));
            assertThat(sale.position.buckets()).isEqualTo(new StockBuckets(7,2,3,0));
            assertThat(sale.position.buckets().available()).isEqualTo(2);
            assertThat(number("SELECT sum(delta_on_hand) FROM stockrescue.stock_movement_line WHERE position_id=?",f.position)).isEqualTo(7);
            assertThat(number("SELECT sum(delta_reserved) FROM stockrescue.stock_movement_line WHERE position_id=?",f.position)).isEqualTo(2);
            assertThat(number("SELECT sum(delta_quarantined) FROM stockrescue.stock_movement_line WHERE position_id=?",f.position)).isEqualTo(3);
            assertThat(number("SELECT sum(delta_other_blocked) FROM stockrescue.stock_movement_line WHERE position_id=?",f.position)).isZero();
            assertThat(number("SELECT sum(remaining_quantity) FROM stockrescue.stock_allocation WHERE position_id=?",f.position)).isEqualTo(5);
            reject("23514",()->sql("UPDATE stockrescue.stock_allocation SET remaining_quantity=1,state='ACTIVE',version=version+1 WHERE id=?",held));
            reject("23514",()->sql("UPDATE stockrescue.stock_allocation SET owner_user_id=?,remaining_quantity=1,version=version+1 WHERE id=?",UUID.randomUUID(),reserved));
            constraints();
        });
    }

    @Test void databaseChecksRejectInvalidBucketsEvenWithMatchingMovementDeltas() {
        within(f->{
            post(f,Operation.RECEIVE,new Delta(3,0,0,0));
            for (var values:List.of(new long[]{-1,0,0,0},new long[]{3,-1,0,0},new long[]{3,0,-1,0},
                    new long[]{3,0,0,-1},new long[]{3,2,2,0},new long[]{3,0,0,4})) {
                reject("23514",()->{
                    var command=command(f,Operation.ADJUST); repository.recordCommand(command);
                    var movement=UUID.randomUUID(); repository.recordMovement(movement,command,"Physical correction","count-sheet");
                    sql("INSERT INTO stockrescue.stock_movement_line(org_id,store_id,movement_id,position_id,position_version,delta_on_hand,delta_reserved,delta_quarantined,delta_other_blocked,initialization_after,trust_after) VALUES (?,?,?,?,2,?,?,?,?,'INITIALIZED','UNVERIFIED')",f.org,f.store,movement,f.position,values[0]-3,values[1],values[2],values[3]);
                    sql("UPDATE stockrescue.stock_position SET on_hand=?,reserved=?,quarantined=?,other_blocked=?,version=2 WHERE id=?",values[0],values[1],values[2],values[3],f.position);
                });
            }
            constraints();
        });
    }

    @Test void unfinishedCommandsAndMismatchedAllocationEvidenceCannotCommit() {
        within(f->{
            reject("23514",()->{ repository.recordCommand(command(f,Operation.RECEIVE)); constraints(); });
            post(f,Operation.RECEIVE,new Delta(3,0,0,0));
            reject("23514",()->{
                var reserve=post(f,Operation.RESERVE,new Delta(0,2,0,0));
                openAllocation(f,reserve,"RESERVED",1);
                constraints();
            });
            var reserve=post(f,Operation.RESERVE,new Delta(0,2,0,0));
            var allocation=openAllocation(f,reserve,"RESERVED",2);
            constraints();
            reject("23503",()->{
                var release=post(f,Operation.RELEASE,new Delta(0,-1,0,0));
                transition(f,release,allocation,"RESERVED",1,3,1,"ACTIVE","RELEASE");
                constraints();
            });
            constraints();
        });
    }

    @Test void malformedCommandReceiptAndPhysicalReceiptFieldsAreRejected() {
        within(f->{
            for (var outcome:List.of("[]","null","1")) {
                reject("23514",()->sql("INSERT INTO stockrescue.inventory_command_receipt(id,org_id,store_id,actor_user_id,membership_id,operation,request_id,payload_digest,outcome) VALUES (?,?,?,?,?,'RECEIVE',?,?,?::jsonb)",UUID.randomUUID(),f.org,f.store,f.user,f.member,UUID.randomUUID(),new byte[32],outcome));
            }
            reject("23503",()->sql("INSERT INTO stockrescue.inventory_command_receipt(id,org_id,store_id,actor_user_id,membership_id,operation,request_id,payload_digest,outcome) VALUES (?,?,?,?,?,'RECEIVE',?,?,'{}')",UUID.randomUUID(),f.otherOrg,f.otherStore,f.user,f.member,UUID.randomUUID(),new byte[32]));
            reject("23514",()->{
                var command=command(f,Operation.RECEIVE); repository.recordCommand(command);
                repository.recordMovement(UUID.randomUUID(),command," ","proof");
            });
            var posted=post(f,Operation.RECEIVE,new Delta(1,0,0,0));
            var receipt=UUID.randomUUID(); receipt(f,posted,receipt,"document-check");
            reject("23514",()->sql("INSERT INTO stockrescue.goods_receipt_line(org_id,store_id,receipt_id,movement_id,position_id,document_line_reference,quantity,disposition) VALUES (?,?,?,?,?,'1',0,'AVAILABLE')",f.org,f.store,receipt,posted.movement,f.position));
            reject("23514",()->sql("INSERT INTO stockrescue.goods_receipt_line(org_id,store_id,receipt_id,movement_id,position_id,document_line_reference,quantity,disposition) VALUES (?,?,?,?,?,'1',1,'RESERVED')",f.org,f.store,receipt,posted.movement,f.position));
            reject("23514",()->sql("INSERT INTO stockrescue.inventory_outbox_event(id,org_id,store_id,movement_id,position_id,aggregate_version,event_type,schema_version,payload) VALUES (?,?,?,?,?,1,'InventoryStockChanged',1,'{}')",UUID.randomUUID(),f.org,f.store,posted.movement,f.position));
            constraints();
        });
    }

    @Test void zeroVerificationAndProjectionUpdatesRequireDurableJournalEvidence() {
        within(f->{
            var zero=post(f,Operation.VERIFY_ZERO,new Delta(0,0,0,0));
            assertThat(zero.position.initialization()).isEqualTo(Initialization.INITIALIZED);
            assertThat(zero.position.buckets()).isEqualTo(StockBuckets.ZERO);
            reject("23514",()->sql("UPDATE stockrescue.stock_position SET initialization_state='NOT_INITIALIZED',version=version+1 WHERE id=?",f.position));
            reject("23514",()->{
                var command=command(f,Operation.RECEIVE);
                repository.recordCommand(command); var movement=UUID.randomUUID(); repository.recordMovement(movement,command,"Delivery","delivery-1");
                sql("INSERT INTO stockrescue.stock_movement_line(org_id,store_id,movement_id,position_id,position_version,delta_on_hand,delta_reserved,delta_quarantined,delta_other_blocked,initialization_after,trust_after) VALUES (?,?,?,?,2,1,0,0,0,'INITIALIZED','UNVERIFIED')",f.org,f.store,movement,f.position);
                constraints();
            });
            constraints();
        });
    }

    @Test void reservationsCannotCommitWithoutTheirMatchingImmutableLifecycle() {
        within(f->{
            post(f,Operation.RECEIVE,new Delta(5,0,0,0));
            reject("23514",()->{ post(f,Operation.RESERVE,new Delta(0,1,0,0)); constraints(); });
            var reserve=post(f,Operation.RESERVE,new Delta(0,2,0,0));
            var allocation=openAllocation(f,reserve,"RESERVED",2);
            constraints();
            reject("23514",()->sql("UPDATE stockrescue.stock_allocation SET remaining_quantity=-1,version=version+1 WHERE id=?",allocation));
            reject("23503",()->{
                sql("UPDATE stockrescue.stock_allocation SET remaining_quantity=1,version=version+1 WHERE id=?",allocation);
                constraints();
            });
            var release=post(f,Operation.RELEASE,new Delta(0,-1,0,0));
            transition(f,release,allocation,"RESERVED",1,2,1,"ACTIVE","RELEASE");
            constraints();
        });
    }

    @Test void commandAndPhysicalDocumentDeduplicationHaveIndependentScopedIdentities() {
        within(f->{
            var posted=post(f,Operation.RECEIVE,new Delta(2,0,0,0));
            assertThat(repository.recordCommand(posted.command)).isFalse();
            var recorded=repository.command(f.org,f.user,Operation.RECEIVE,posted.command.requestId()).orElseThrow();
            assertThat(recorded.id()).isEqualTo(posted.command.id());
            assertThat(recorded.payloadDigest()).isEqualTo(posted.command.payloadDigest());
            var receipt=UUID.randomUUID(); receipt(f,posted,receipt,"delivery-24");
            sql("INSERT INTO stockrescue.goods_receipt_line(org_id,store_id,receipt_id,movement_id,position_id,document_line_reference,quantity,disposition) VALUES (?,?,?,?,?,'1',2,'AVAILABLE')",f.org,f.store,receipt,posted.movement,f.position);
            var newRequest=post(f,Operation.RECEIVE,new Delta(1,0,0,0));
            reject("23505",()->receipt(f,newRequest,UUID.randomUUID(),"delivery-24"));
            reject("23505",()->sql("INSERT INTO stockrescue.goods_receipt_line(org_id,store_id,receipt_id,movement_id,position_id,document_line_reference,quantity,disposition) VALUES (?,?,?,?,?,'1',1,'AVAILABLE')",f.org,f.store,receipt,posted.movement,f.position));
            reject("23503",()->sql("INSERT INTO stockrescue.goods_receipt_line(org_id,store_id,receipt_id,movement_id,position_id,document_line_reference,quantity,disposition) VALUES (?,?,?,?,?,'2',1,'AVAILABLE')",f.otherOrg,f.otherStore,receipt,posted.movement,f.position));
            reject("23514",()->sql("INSERT INTO stockrescue.inventory_command_receipt(id,org_id,store_id,actor_user_id,membership_id,operation,request_id,payload_digest,outcome) VALUES (?,?,?,?,?,'RECEIVE',?,decode('00','hex'),'{}')",UUID.randomUUID(),f.org,f.store,f.user,f.member,UUID.randomUUID()));
            constraints();
        });
    }

    @Test void historyAndDerivedEvidenceRejectUpdateDeleteAndTruncate() {
        within(f->{
            var posted=post(f,Operation.RECEIVE,new Delta(3,0,0,0));
            var receipt=UUID.randomUUID(); receipt(f,posted,receipt,"immutable-delivery");
            sql("INSERT INTO stockrescue.goods_receipt_line(org_id,store_id,receipt_id,movement_id,position_id,document_line_reference,quantity,disposition) VALUES (?,?,?,?,?,'1',3,'AVAILABLE')",f.org,f.store,receipt,posted.movement,f.position);
            var reservation=post(f,Operation.RESERVE,new Delta(0,1,0,0)); openAllocation(f,reservation,"RESERVED",1);
            var run=UUID.randomUUID();
            sql("INSERT INTO stockrescue.inventory_reconciliation_run(id,org_id,store_id,actor_user_id,membership_id,severity,checked_positions,started_at,completed_at) VALUES (?,?,?,?,?,'CLEAN',1,clock_timestamp(),clock_timestamp())",run,f.org,f.store,f.user,f.member);
            sql("INSERT INTO stockrescue.inventory_reconciliation_result(org_id,store_id,run_id,position_id,position_version,severity,finding_code,expected_buckets,observed_buckets) VALUES (?,?,?,?,2,'CLEAN','MATCH','{}','{}')",f.org,f.store,run,f.position);
            var event=UUID.fromString(text("SELECT id FROM stockrescue.inventory_outbox_event WHERE movement_id=?",posted.movement));
            sql("INSERT INTO stockrescue.inventory_inbox(consumer_name,event_id,org_id,store_id,position_id,aggregate_version,payload_digest) VALUES ('inventory-feed',?,?,?,?,1,?)",event,f.org,f.store,f.position,new byte[32]);
            sql("INSERT INTO stockrescue.inventory_change_feed(consumer_name,event_id,org_id,store_id,position_id,aggregate_version,change) VALUES ('inventory-feed',?,?,?,?,1,'{}')",event,f.org,f.store,f.position);
            constraints();
            for (var table:List.of("inventory_command_receipt","stock_movement","stock_movement_line","goods_receipt","goods_receipt_line",
                    "stock_allocation_transition","inventory_reconciliation_run","inventory_reconciliation_result","inventory_outbox_event","inventory_inbox","inventory_change_feed")) {
                reject("55000",()->sql("UPDATE stockrescue."+table+" SET org_id=org_id WHERE org_id=?",f.org));
                reject("55000",()->sql("DELETE FROM stockrescue."+table+" WHERE org_id=?",f.org));
                reject("55000",()->sql("TRUNCATE stockrescue."+table+" CASCADE"));
            }
            for (var table:List.of("stock_position","stock_allocation","inventory_outbox_delivery")) {
                reject("55000",()->sql("DELETE FROM stockrescue."+table+" WHERE org_id=?",f.org));
                reject("55000",()->sql("TRUNCATE stockrescue."+table+" CASCADE"));
            }
        });
    }

    @Test void outboxInboxAndReconciliationRejectScopeAliasesInvalidPayloadsAndDuplicateEffects() {
        within(f->{
            var posted=post(f,Operation.RECEIVE,new Delta(1,0,0,0));
            var event=UUID.fromString(text("SELECT id FROM stockrescue.inventory_outbox_event WHERE movement_id=?",posted.movement));
            reject("23514",()->sql("UPDATE stockrescue.inventory_outbox_delivery SET lease_owner=? WHERE event_id=?",UUID.randomUUID(),event));
            reject("23514",()->sql("UPDATE stockrescue.inventory_outbox_delivery SET lease_owner=?,lease_until=clock_timestamp()+interval '30 seconds' WHERE event_id=?",UUID.randomUUID(),event));
            reject("23514",()->sql("UPDATE stockrescue.inventory_outbox_delivery SET attempt_count=-1 WHERE event_id=?",event));
            reject("23514",()->sql("UPDATE stockrescue.inventory_outbox_delivery SET org_id=?,store_id=? WHERE event_id=?",f.otherOrg,f.otherStore,event));
            reject("23503",()->sql("INSERT INTO stockrescue.inventory_inbox(consumer_name,event_id,org_id,store_id,position_id,aggregate_version,payload_digest) VALUES ('inventory-feed',?,?,?,?,1,?)",event,f.otherOrg,f.otherStore,f.position,new byte[32]));
            reject("23514",()->sql("INSERT INTO stockrescue.inventory_inbox(consumer_name,event_id,org_id,store_id,position_id,aggregate_version,payload_digest) VALUES ('inventory-feed',?,?,?,?,0,?)",event,f.org,f.store,f.position,new byte[32]));
            sql("INSERT INTO stockrescue.inventory_inbox(consumer_name,event_id,org_id,store_id,position_id,aggregate_version,payload_digest) VALUES ('inventory-feed',?,?,?,?,1,?)",event,f.org,f.store,f.position,new byte[32]);
            reject("23505",()->sql("INSERT INTO stockrescue.inventory_inbox(consumer_name,event_id,org_id,store_id,position_id,aggregate_version,payload_digest) VALUES ('inventory-feed',?,?,?,?,1,?)",UUID.randomUUID(),f.org,f.store,f.position,new byte[32]));
            reject("23503",()->sql("INSERT INTO stockrescue.inventory_change_feed(consumer_name,event_id,org_id,store_id,position_id,aggregate_version,change) VALUES ('inventory-feed',?,?,?,?,1,'{}')",event,f.otherOrg,f.otherStore,f.position));
            reject("23503",()->sql("INSERT INTO stockrescue.inventory_reconciliation_result(org_id,store_id,run_id,position_id,position_version,severity,finding_code,expected_buckets,observed_buckets) VALUES (?,?,?,?,1,'CLEAN','MATCH','{}','{}')",f.otherOrg,f.otherStore,UUID.randomUUID(),f.position));
            constraints();
        });
    }

    @Test void aFailureAfterAuditAndOutboxLeavesNoPartialInventoryEffect() {
        var f=transactions.execute(status->fixture());
        try (var correlation=org.slf4j.MDC.putCloseable("correlationId",UUID.randomUUID().toString())) {
            assertThatThrownBy(()->transactions.executeWithoutResult(status->{
                post(f,Operation.RECEIVE,new Delta(5,0,0,0));
                new AuditWriter(jdbc,new ObjectMapper()).organizationAction(f.user,f.org,"stock_position",f.position,"GOODS_RECEIVED");
                throw new IllegalStateException("injected failure after all inserts");
            })).isInstanceOf(IllegalStateException.class).hasMessage("injected failure after all inserts");
        }
        assertThat(repository.position(f.org,f.store,f.position,false).orElseThrow().version()).isZero();
        for (var table:List.of("inventory_command_receipt","stock_movement","stock_movement_line","inventory_outbox_event","inventory_outbox_delivery","audit_entry")) {
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue."+table+" WHERE org_id=:org").param("org",f.org).query(Long.class).single()).as(table).isZero();
        }
    }

    @Test void inventoryPrimitivesRequireAWriteTransactionAndRejectStaleVersions() {
        assertThatThrownBy(()->repository.createPosition(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        within(f->{
            var posted=post(f,Operation.RECEIVE,new Delta(1,0,0,0));
            assertThatThrownBy(()->repository.postLine(f.org,f.store,posted.movement,f.position,0,new Delta(1,0,0,0),Trust.UNVERIFIED))
                    .isInstanceOf(InventoryWriteConflict.class);
            assertThat(number("SELECT count(*) FROM stockrescue.stock_movement_line WHERE position_id=?",f.position)).isEqualTo(1);
            sql("SET LOCAL enable_seqscan=off");
            assertThat(text("EXPLAIN (COSTS OFF) SELECT id FROM stockrescue.stock_position WHERE org_id=? AND store_id=? AND product_id=? ORDER BY lot_id,location_id,id",f.org,f.store,f.product)).contains("stock_position_product_page_idx");
            constraints();
        });
    }

    private static PostgreSQLContainer database() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine")).withDatabaseName("stockrescue_test")
                .withUsername("test_admin").withPassword("test-admin-password").withInitScript("db/test-role.sql");
    }
    private static DriverManagerDataSource dataSource(PostgreSQLContainer database) {
        var bindings=database.getDockerClient().inspectContainerCmd(database.getContainerId()).exec().getNetworkSettings().getPorts().getBindings();
        var port=bindings.get(com.github.dockerjava.api.model.ExposedPort.tcp(5432))[0].getHostPortSpec();
        return new DriverManagerDataSource("jdbc:postgresql://"+database.getHost()+":"+port+"/"+database.getDatabaseName(),"stockrescue_app","test-app-password");
    }
    private static Flyway migrations(DriverManagerDataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).defaultSchema("public").cleanDisabled(true).load();
    }
    private void within(Work<Fixture> work) {
        transactions.executeWithoutResult(status->{
            try { work.run(fixture()); }
            catch (Exception error) { throw new RuntimeException(error); }
            finally { status.setRollbackOnly(); }
        });
    }
    private static Fixture fixture() {
        var f=new Fixture(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        execute("INSERT INTO stockrescue.user_account(id,email_display) VALUES (?,?)",f.user,f.user+"@example.test");
        execute("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (?,'Retailer',?),(?,'Other retailer',?)",f.org,f.user,f.otherOrg,f.user);
        execute("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (?,?,?,'ORG_ADMIN')",f.member,f.org,f.user);
        execute("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (?,?,'NORTH','North','UTC'),(?,?,'SOUTH','South','UTC')",f.store,f.org,f.otherStore,f.otherOrg);
        execute("INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES (?,?,?,'Dry','AMBIENT')",f.zone,f.org,f.store);
        execute("INSERT INTO stockrescue.storage_location(id,org_id,store_id,zone_id,code,name) VALUES (?,?,?,?,'A-1','Aisle one')",f.location,f.org,f.store,f.zone);
        execute("INSERT INTO stockrescue.product(id,org_id,sku,name,base_unit,temperature_class) VALUES (?,?,'BREAD','Bread','EA','AMBIENT')",f.product,f.org);
        execute("INSERT INTO stockrescue.manufacturer_lot(id,org_id,product_id,lot_code) VALUES (?,?,?,'LOT-A')",f.lot,f.org,f.product);
        repository.createPosition(f.position,f.org,f.store,f.location,f.product,f.lot);
        return f;
    }
    private static CommandReceipt command(Fixture f,Operation operation) {
        return new CommandReceipt(UUID.randomUUID(),f.org,f.store,f.user,f.member,operation,UUID.randomUUID(),new byte[32],"{\"accepted\":true}");
    }
    private static Posting post(Fixture f,Operation operation,Delta delta) {
        var command=command(f,operation); repository.recordCommand(command); var movement=UUID.randomUUID();
        repository.recordMovement(movement,command,"Operator stock evidence","manual-document");
        var position=repository.position(f.org,f.store,f.position,true).orElseThrow();
        return new Posting(movement,repository.postLine(f.org,f.store,movement,f.position,position.version(),delta,Trust.UNVERIFIED),command);
    }
    private static UUID openAllocation(Fixture f,Posting posting,String bucket,long amount) {
        var id=UUID.randomUUID();
        execute("INSERT INTO stockrescue.stock_allocation(id,org_id,store_id,position_id,bucket,owner_user_id,owner_membership_id,initial_quantity,remaining_quantity,state) VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE')",id,f.org,f.store,f.position,bucket,f.user,f.member,amount,amount);
        execute("INSERT INTO stockrescue.stock_allocation_transition(org_id,store_id,allocation_id,position_id,bucket,movement_id,version,remaining_before,remaining_after,state_after,transition) VALUES (?,?,?,?,?,?,0,0,?,'ACTIVE','CREATE')",f.org,f.store,id,f.position,bucket,posting.movement,amount);
        return id;
    }
    private static void transition(Fixture f,Posting posting,UUID allocation,String bucket,long version,long before,long after,String state,String action) {
        execute("UPDATE stockrescue.stock_allocation SET remaining_quantity=?,state=?,version=? WHERE id=?",after,state,version,allocation);
        execute("INSERT INTO stockrescue.stock_allocation_transition(org_id,store_id,allocation_id,position_id,bucket,movement_id,version,remaining_before,remaining_after,state_after,transition) VALUES (?,?,?,?,?,?,?,?,?,?,?)",f.org,f.store,allocation,f.position,bucket,posting.movement,version,before,after,state,action);
    }
    private static void receipt(Fixture f,Posting posting,UUID id,String document) {
        execute("INSERT INTO stockrescue.goods_receipt(id,org_id,store_id,movement_id,supplier_key,document_type,document_reference,physical_receipt_at) VALUES (?,?,?,?,'supplier-1','DELIVERY',?,clock_timestamp())",id,f.org,f.store,posting.movement,document);
    }
    private static void insertPosition(UUID id,UUID org,UUID store,UUID location,UUID product,UUID lot) {
        execute("INSERT INTO stockrescue.stock_position(id,org_id,store_id,location_id,product_id,lot_id) VALUES (?,?,?,?,?,?)",id,org,store,location,product,lot);
    }
    private static void execute(String query,Object...args) {
        try { sql(query,args); } catch (SQLException error) { throw new org.springframework.jdbc.UncategorizedSQLException("fixture",query,error); }
    }
    private static void sql(String query,Object...args) throws SQLException {
        try (var statement=connection().prepareStatement(query)) {
            for(int index=0;index<args.length;index++) statement.setObject(index+1,args[index]);
            statement.setQueryTimeout(10); statement.execute();
        }
    }
    private static String text(String query,Object...args) throws SQLException {
        try(var statement=connection().prepareStatement(query)) {
            for(int index=0;index<args.length;index++) statement.setObject(index+1,args[index]);
            try(var rows=statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
        }
    }
    private static long number(String query,Object...args) throws SQLException { return Long.parseLong(text(query,args)); }
    private static Connection connection() { return DataSourceUtils.getConnection(source); }
    private static void constraints() throws SQLException { sql("SET CONSTRAINTS ALL IMMEDIATE"); sql("SET CONSTRAINTS ALL DEFERRED"); }
    private static void reject(String state,SqlWork work) throws Exception {
        var savepoint=connection().setSavepoint();
        try {
            assertThatThrownBy(work::run).satisfies(error->{
                Throwable root=error; while(root.getCause()!=null) root=root.getCause();
                assertThat(root).isInstanceOfSatisfying(SQLException.class,sqlError->assertThat(sqlError.getSQLState()).isEqualTo(state));
            });
        } finally { connection().rollback(savepoint); connection().releaseSavepoint(savepoint); }
    }
    private static void rejectJava(Class<? extends Throwable> type,SqlWork work) throws Exception {
        var savepoint=connection().setSavepoint();
        try { assertThatThrownBy(work::run).isInstanceOf(type); }
        finally { connection().rollback(savepoint); connection().releaseSavepoint(savepoint); }
    }
    private record Fixture(UUID user,UUID org,UUID otherOrg,UUID member,UUID store,UUID otherStore,UUID zone,UUID location,UUID product,UUID lot,UUID position) {}
    private record Posting(UUID movement,StockPosition position,CommandReceipt command) {}
    @FunctionalInterface private interface Work<T> { void run(T value) throws Exception; }
    @FunctionalInterface private interface SqlWork { void run() throws Exception; }
}
