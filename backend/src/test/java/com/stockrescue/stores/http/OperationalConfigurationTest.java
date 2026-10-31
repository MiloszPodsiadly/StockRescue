package com.stockrescue.stores.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.support.InfrastructureTestBase;
import com.stockrescue.stores.domain.CostParameters;
import com.stockrescue.stores.persistence.LaneRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationalConfigurationTest extends InfrastructureTestBase {
    private static final String PASSWORD="operational-settings-passphrase";
    private static final String REASON="Reviewed operational configuration";
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder encoder;
    @Autowired private LaneRepository laneRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private AuditWriter audit;
    @MockitoSpyBean private Clock clock;
    private String hash;
    private Fixture f;
    @BeforeAll void passwordFixture() { hash=encoder.encode(PASSWORD); }
    @BeforeEach void configurationFixture() { f=new Fixture(); }

    @Test
    void assignedManagersConfigureZonesWhileReadRolesAndForeignIdentifiersAreDenied() throws Exception {
        var manager=f.manager.browser();
        var created=manager.write("POST",zones(f.orgA,f.a1),Map.of("name"," Secondary ","temperatureClass","CHILLED"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var zone=tree(created);var id=UUID.fromString(zone.path("id").asString());
        problem(manager.write("POST",zones(f.orgA,f.a1),Map.of("name","secondary","temperatureClass","AMBIENT")),409,"CONFIGURATION_CONFLICT");
        assertThat(f.ownerA.browser().write("POST",zones(f.orgA,f.a2),Map.of("name","Secondary","temperatureClass","CHILLED")).statusCode()).isEqualTo(201);
        problem(manager.write("POST",zones(f.orgA,f.a2),Map.of("name","Forbidden","temperatureClass","AMBIENT")),404,"RESOURCE_NOT_FOUND");
        problem(f.reader.browser().write("POST",zones(f.orgA,f.a1),Map.of("name","Forbidden","temperatureClass","AMBIENT")),403,"ACCESS_DENIED");
        assertThat(tree(f.reader.browser().get(zones(f.orgA,f.a1))).path("canManage").asBoolean()).isFalse();
        problem(f.ownerA.browser().get(zones(f.orgA,f.b1)),404,"RESOURCE_NOT_FOUND");
        var renamed=manager.write("PATCH",zones(f.orgA,f.a1)+"/"+id,Map.of("name","Renamed zone","version",0,"reason",REASON));
        assertThat(renamed.statusCode()).isEqualTo(200);
        problem(manager.write("PATCH",zones(f.orgA,f.a1)+"/"+id,Map.of("name","Stale","version",0,"reason",REASON)),409,"CONFIGURATION_CONFLICT");
        problem(manager.write("PATCH",zones(f.orgA,f.a1)+"/"+id,Map.of("name","Forged","temperatureClass","FROZEN","version",1,"reason",REASON)),400,"INVALID_REQUEST");
        assertThat(manager.write("POST",zones(f.orgA,f.a1)+"/"+id+"/archive",version(1)).statusCode()).isEqualTo(200);
        problem(manager.write("PATCH",zones(f.orgA,f.a1)+"/"+id,Map.of("name","Inactive","version",2,"reason",REASON)),409,"CONFIGURATION_INACTIVE");
        assertThat(tree(manager.get(zones(f.orgA,f.a1)+"?includeInactive=true&limit=1")).path("hasMore").asBoolean()).isTrue();
        assertThatThrownBy(()->jdbc.sql("UPDATE stockrescue.storage_zone SET temperature_class='FROZEN' WHERE id=:id").param("id",id).update()).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void lanesRequireTwoActiveSameTenantStoresAndCompatibleEnabledZones() throws Exception {
        var owner=f.ownerA.browser();
        problem(owner.write("POST",lanes(f.orgA),laneBody(f.a1,f.a1,f.z1,f.z1)),400,"INVALID_REQUEST");
        problem(owner.write("POST",lanes(f.orgA),laneBody(f.a1,f.b1,f.z1,f.zb1)),404,"RESOURCE_NOT_FOUND");
        problem(owner.write("POST",lanes(f.orgA),laneBody(f.a1,f.a2,f.z1,f.zb1)),404,"RESOURCE_NOT_FOUND");
        problem(owner.write("POST",lanes(f.orgA),laneBody(f.a1,f.a2,f.z2,f.z1)),404,"RESOURCE_NOT_FOUND");
        problem(owner.write("POST",lanes(f.orgA),laneBody(f.a1,f.a2,f.z1,f.chilled2)),409,"INCOMPATIBLE_ZONES");
        var configured=createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);
        assertThat(configured.path("status").asString()).isEqualTo("UNCONFIGURED");
        assertThat(configured.path("currentCost").isNull()).isTrue();
        problem(owner.write("POST",lanes(f.orgA),laneBody(f.a1,f.a2,f.z1,f.z2)),409,"CONFIGURATION_CONFLICT");
        var foreign=createLane(f.ownerB,f.orgB,f.b1,f.b2,f.zb1,f.zb2);
        problem(owner.get(lanes(f.orgA)+"/"+foreign.path("id").asString()),404,"RESOURCE_NOT_FOUND");
        problem(owner.write("PATCH",lanes(f.orgA)+"/"+foreign.path("id").asString(),laneUpdate(0,f.z1,f.z2,true)),404,"RESOURCE_NOT_FOUND");
        problem(owner.write("POST",lanes(f.orgA)+"/"+foreign.path("id").asString()+"/revisions",revision(0,0,"12.0000","EUR",null)),404,"RESOURCE_NOT_FOUND");
        assertThatThrownBy(()->jdbc.sql("INSERT INTO stockrescue.transport_lane(id,org_id,origin_store_id,destination_store_id,origin_zone_id,destination_zone_id) VALUES (:id,:org,:a,:b,:za,:zb)")
                .param("id",UUID.randomUUID()).param("org",f.orgA).param("a",f.a2).param("b",f.a1).param("za",f.zb1).param("zb",f.z1).update()).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void missingRoutesAndScopedPagesNeverInventCostOrRevealForeignCounts() throws Exception {
        var owner=f.ownerA.browser();
        var missing=tree(owner.get(lanes(f.orgA)+"/lookup?originStoreId="+f.a3+"&destinationStoreId="+f.a4));
        assertThat(missing.path("status").asString()).isEqualTo("UNCONFIGURED");
        assertThat(missing.path("lane").isNull()).isTrue();
        assertThat(missing.path("missing").toString()).contains("NO_LANE");
        createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);
        createLane(f.ownerA,f.orgA,f.a2,f.a1,f.z2,f.z1);
        var foreign=createLane(f.ownerB,f.orgB,f.b1,f.b2,f.zb1,f.zb2);
        var page=tree(owner.get(lanes(f.orgA)+"?limit=1&organizationId="+f.orgB));
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        assertThat(page.toString()).doesNotContain(foreign.path("id").asString(),"Foreign", "total");
        assertThat(tree(owner.get(lanes(f.orgA)+"?offset=2")).path("items").isEmpty()).isTrue();
        problem(f.manager.browser().get(lanes(f.orgA)),403,"ACCESS_DENIED");
        problem(f.ownerB.browser().get(lanes(f.orgA)),404,"RESOURCE_NOT_FOUND");
        problem(owner.get(lanes(f.orgA)+"/lookup?originStoreId="+f.a1+"&destinationStoreId="+f.b1),404,"RESOURCE_NOT_FOUND");
        problem(owner.get(lanes(f.orgA)+"?limit=101"),400,"INVALID_REQUEST");
    }

    @Test
    void costsRemainExactDecimalStringsAndNewPricesUseSupportedIsoCurrencies() throws Exception {
        var lane=createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);var id=UUID.fromString(lane.path("id").asString());var owner=f.ownerA.browser();
        var result=owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(0,0,"999999999999999.9999","PLN",null));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        assertThat(tree(result).path("currentCost").path("costAmount").asString()).isEqualTo("999999999999999.9999");
        assertThat(jdbc.sql("SELECT cost_amount::text FROM stockrescue.lane_revision WHERE lane_id=:id").param("id",id).query(String.class).single()).isEqualTo("999999999999999.9999");
        var currencies=tree(owner.get(lanes(f.orgA)+"/currencies")).path("codes").toString();
        assertThat(currencies).contains("PLN","EUR","USD","JPY").doesNotContain("BYR","XXX","XTS");
        for(var amount:List.of("-1","NaN","1e3","0.00001","1000000000000000")) problem(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(1,1,amount,"EUR",null)),400,"INVALID_REQUEST");
        for(var currency:List.of("BYR","XXX","XTS","QQQ","eur")) problem(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(1,1,"1.2300",currency,null)),400,"INVALID_REQUEST");
        var invalid=revision(1,1,"1.23","EUR",null);invalid.put("durationMinutes",0);
        problem(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",invalid),400,"INVALID_REQUEST");
        var invalidReason=revision(1,1,"1.23","EUR",null);invalidReason.put("reason","Invalid\u0000reason");
        problem(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",invalidReason),400,"INVALID_REQUEST");
        var zero=owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(1,1,"0.0000","EUR",null));
        assertThat(zero.statusCode()).isEqualTo(201);
        assertThat(tree(zero).path("status").asString()).isEqualTo("CONFIGURED");
        assertThat(tree(zero).path("currentCost").path("costAmount").asString()).isEqualTo("0.0000");
    }

    @Test
    void scheduledRevisionsPreserveHistoryAndHaveNonOverlappingValidityIntervals() throws Exception {
        var lane=createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);var id=UUID.fromString(lane.path("id").asString());var owner=f.ownerA.browser();
        assertThat(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(0,0,"12.3456","EUR",null)).statusCode()).isEqualTo(201);
        var now=Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);doReturn(now).when(clock).instant();
        var future=now.plusSeconds(3600);
        var planned=owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(1,1,"14.0001","EUR",future));
        assertThat(planned.statusCode()).isEqualTo(201);
        assertThat(tree(planned).path("currentCost").path("costAmount").asString()).isEqualTo("12.3456");
        var history=tree(owner.get(lanes(f.orgA)+"/"+id+"/revisions")).path("items");
        assertThat(history).hasSize(2);
        assertThat(Instant.parse(history.get(1).path("validUntil").asString())).isEqualTo(future);
        assertThat(history.get(0).path("validUntil").isNull()).isTrue();
        problem(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(2,2,"15.0000","EUR",future)),409,"CONFIGURATION_CONFLICT");
        problem(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(2,2,"15.0000","EUR",now.minusSeconds(1))),400,"INVALID_REQUEST");
        problem(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(2,2,"15.0000","EUR",future.plusNanos(1))),400,"INVALID_REQUEST");
        doReturn(future.plusSeconds(1)).when(clock).instant();
        assertThat(tree(owner.get(lanes(f.orgA)+"/"+id)).path("currentCost").path("costAmount").asString()).isEqualTo("14.0001");
        assertThat(tree(owner.get(lanes(f.orgA)+"/"+id+"/revisions")).path("items")).isEqualTo(history);
        assertThatThrownBy(()->jdbc.sql("UPDATE stockrescue.lane_revision SET cost_amount=0 WHERE lane_id=:id").param("id",id).update()).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void cancellingFutureCostsPreservesEvidenceAndAllowsImmediateReplacementAfterZoneChange() throws Exception {
        var lane=createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);var id=UUID.fromString(lane.path("id").asString());var owner=f.ownerA.browser();
        var current=owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(0,0,"12.3456","EUR",null));
        assertThat(current.statusCode()).as(current.body()).isEqualTo(201);
        var currentAt=Instant.parse(tree(current).path("currentCost").path("effectiveAt").asString());
        problem(owner.write("POST",cancelPath(f.orgA,id),cancellation(1,1)),409,"NO_FUTURE_REVISIONS");
        assertThat(tree(owner.get(lanes(f.orgA)+"/"+id)).path("version").asLong()).isEqualTo(1);
        var now=Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);doReturn(now).when(clock).instant();
        var tomorrow=now.plus(1,ChronoUnit.DAYS);
        assertThat(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(1,1,"14.0001","EUR",tomorrow)).statusCode()).isEqualTo(201);
        var later=tomorrow.plus(1,ChronoUnit.HOURS);
        assertThat(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(2,2,"15.0001","EUR",later)).statusCode()).isEqualTo(201);

        var member=membership(f.orgA,f.ownerA.id);
        var cancelled=new TransactionTemplate(transactionManager).execute(status ->
                laneRepository.cancelFutureRevisions(f.orgA,id,member,REASON,"schedule-resolution-test"));
        assertThat(cancelled).hasSize(2).extracting(item->item.revision()).containsExactly(2L,3L);
        assertThat(cancelled).extracting(item->item.cancelledByMembershipId()).containsOnly(member);
        assertThat(cancelled).extracting(item->item.reason()).containsOnly(REASON);
        assertThat(cancelled).extracting(item->item.correlationId()).containsOnly("schedule-resolution-test");

        var afterCancellation=tree(owner.get(lanes(f.orgA)+"/"+id));
        assertThat(afterCancellation.path("latestRevision").asLong()).isEqualTo(3);
        assertThat(Instant.parse(afterCancellation.path("latestEffectiveAt").asString())).isEqualTo(currentAt);
        assertThat(afterCancellation.path("currentCost").path("costAmount").asString()).isEqualTo("12.3456");

        var newOrigin=f.zone(f.orgA,f.a1,"Replacement origin","AMBIENT");
        var newDestination=f.zone(f.orgA,f.a2,"Replacement destination","AMBIENT");
        var changed=owner.write("PATCH",lanes(f.orgA)+"/"+id,laneUpdate(4,newOrigin,newDestination,true));
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(tree(changed).path("status").asString()).isEqualTo("UNCONFIGURED");
        assertThat(tree(changed).path("missing").toString()).contains("COST_ZONE_MISMATCH");
        assertThat(tree(changed).path("currentCost").isNull()).isTrue();

        var replacementAt=Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MICROS);doReturn(replacementAt).when(clock).instant();
        var replacement=owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(5,3,"16.0000","EUR",null));
        assertThat(replacement.statusCode()).as(replacement.body()).isEqualTo(201);
        assertThat(tree(replacement).path("status").asString()).isEqualTo("CONFIGURED");
        assertThat(tree(replacement).path("latestRevision").asLong()).isEqualTo(4);
        assertThat(tree(replacement).path("currentCost").path("costAmount").asString()).isEqualTo("16.0000");

        var history=laneRepository.history(f.orgA,id,0,10);
        assertThat(history).extracting(item->item.revision()).containsExactly(4L,3L,2L,1L);
        assertThat(history.get(0).validUntil()).isNull();
        assertThat(history.get(1).cancellation()).isNotNull();
        assertThat(history.get(1).validUntil()).isNull();
        assertThat(history.get(2).cancellation()).isNotNull();
        assertThat(history.get(2).validUntil()).isNull();
        assertThat(history.get(3).validUntil()).isEqualTo(replacementAt);
        assertThat(history.get(1).cancellation().revisionId()).isEqualTo(history.get(1).id());
        assertThat(history.get(1).cancellation().revision()).isEqualTo(3);

        doReturn(tomorrow).when(clock).instant();
        var atFirstCancelledDate=tree(owner.get(lanes(f.orgA)+"/"+id));
        assertThat(atFirstCancelledDate.path("currentCost").path("costAmount").asString()).isEqualTo("16.0000");
        assertThat(atFirstCancelledDate.path("latestRevision").asLong()).isEqualTo(4);
        doReturn(later.plusSeconds(1)).when(clock).instant();
        assertThat(tree(owner.get(lanes(f.orgA)+"/"+id)).path("currentCost").path("costAmount").asString()).isEqualTo("16.0000");
    }

    @Test
    void failedCancellationAuditRollsBackAllCancellationEvidenceAndLaneVersion() throws Exception {
        var lane=createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);var id=UUID.fromString(lane.path("id").asString());var owner=f.ownerA.browser();
        var current=owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(0,0,"12.0000","EUR",null));
        assertThat(current.statusCode()).as(current.body()).isEqualTo(201);
        var now=Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);doReturn(now).when(clock).instant();
        assertThat(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(1,1,"13.0000","EUR",now.plus(1,ChronoUnit.DAYS))).statusCode()).isEqualTo(201);
        var version=tree(owner.get(lanes(f.orgA)+"/"+id)).path("version").asLong();
        doThrow(new IllegalStateException("Test audit failure")).when(audit).securityChange(any(),eq(f.orgA),eq("transport-lane"),eq(id),
                eq("LANE_SCHEDULED_REVISIONS_CANCELLED"),anyMap(),eq(REASON));

        assertThatThrownBy(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            var evidence=laneRepository.cancelFutureRevisions(f.orgA,id,membership(f.orgA,f.ownerA.id),REASON,"audit-rollback-test");
            assertThat(evidence).hasSize(1);
            audit.securityChange(f.ownerA.id,f.orgA,"transport-lane",id,"LANE_SCHEDULED_REVISIONS_CANCELLED",
                    Map.of("outcome","SUCCEEDED","cancelledRevisions",evidence),REASON);
        })).isInstanceOf(IllegalStateException.class).hasMessage("Test audit failure");

        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.lane_revision_cancellation WHERE org_id=:org AND lane_id=:lane")
                .param("org",f.orgA).param("lane",id).query(Long.class).single()).isZero();
        assertThat(tree(owner.get(lanes(f.orgA)+"/"+id)).path("version").asLong()).isEqualTo(version);
        assertThat(tree(owner.get(lanes(f.orgA)+"/"+id)).path("latestRevision").asLong()).isEqualTo(2);
    }

    @Test
    void laneLockSerializesScheduledCancellationAgainstRevisionApproval() throws Exception {
        var lane=createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);var id=UUID.fromString(lane.path("id").asString());var owner=f.ownerA.browser();
        var current=owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(0,0,"12.0000","EUR",null));
        assertThat(current.statusCode()).as(current.body()).isEqualTo(201);
        var now=Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);doReturn(now).when(clock).instant();
        var tomorrow=now.plus(1,ChronoUnit.DAYS);
        assertThat(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(1,1,"13.0000","EUR",tomorrow)).statusCode()).isEqualTo(201);
        var member=membership(f.orgA,f.ownerA.id);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var cancel=workers.submit(()->{ready.countDown();start.await();return new TransactionTemplate(transactionManager).execute(status->
                    laneRepository.cancelFutureRevisions(f.orgA,id,member,REASON,"concurrent-cancel-test"));});
            var revise=workers.submit(()->{ready.countDown();start.await();new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                laneRepository.lock(f.orgA,id);
                var locked=laneRepository.detail(f.orgA,id).orElseThrow();
                laneRepository.revise(f.orgA,locked,member,CostParameters.parse("14.0000","EUR",45),tomorrow.plusSeconds(1));
            });return null;});
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(cancel.get(20,TimeUnit.SECONDS)).isNotEmpty();revise.get(20,TimeUnit.SECONDS);
        }
        var history=laneRepository.history(f.orgA,id,0,10);
        assertThat(history).extracting(item->item.revision()).containsExactly(3L,2L,1L);
        assertThat(history.get(1).cancellation()).isNotNull();
        assertThat(laneRepository.detail(f.orgA,id).orElseThrow().latestRevision()).isEqualTo(3);
        assertThat(laneRepository.detail(f.orgA,id).orElseThrow().version()).isEqualTo(4);
        assertThat(laneRepository.currentCosts(f.orgA,List.of(id),now).getFirst().revision().costAmount()).isEqualTo("12.0000");
    }

    @Test
    void scheduledCostCancellationIsAuditedAndReconfiguresTheLaneWithoutResurrectingPlans() throws Exception {
        var lane=createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);var id=UUID.fromString(lane.path("id").asString());var owner=f.ownerA.browser();
        var current=owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(0,0,"12.3456","EUR",null));
        assertThat(current.statusCode()).as(current.body()).isEqualTo(201);
        var currentAt=Instant.parse(tree(current).path("currentCost").path("effectiveAt").asString());
        var now=Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);doReturn(now).when(clock).instant();
        var tomorrow=now.plus(1,ChronoUnit.DAYS);var later=tomorrow.plus(1,ChronoUnit.HOURS);
        assertThat(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(1,1,"14.0001","EUR",tomorrow)).statusCode()).isEqualTo(201);
        assertThat(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(2,2,"15.0001","EUR",later)).statusCode()).isEqualTo(201);
        var newOrigin=f.zone(f.orgA,f.a1,"Replacement origin","AMBIENT");
        var newDestination=f.zone(f.orgA,f.a2,"Replacement destination","AMBIENT");
        var lanePath=lanes(f.orgA)+"/"+id;

        problem(owner.write("PATCH",lanePath,laneUpdate(3,newOrigin,newDestination,true)),409,"SCHEDULED_REVISIONS_PENDING");
        assertThat(tree(owner.get(lanePath)).path("version").asLong()).isEqualTo(3);
        assertThat(Instant.parse(tree(owner.get(lanePath)).path("latestEffectiveAt").asString())).isEqualTo(later);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND resource_id=:lane AND action='LANE_UPDATED'")
                .param("org",f.orgA).param("lane",id).query(Long.class).single()).isZero();
        problem(owner.write("POST",cancelPath(f.orgA,id),cancellation(3,2)),409,"CONFIGURATION_CONFLICT");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.lane_revision_cancellation WHERE lane_id=:lane")
                .param("lane",id).query(Long.class).single()).isZero();

        var cancelled=owner.write("POST",cancelPath(f.orgA,id),cancellation(3,3));
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        var result=tree(cancelled);
        assertThat(result.path("lane").path("version").asLong()).isEqualTo(4);
        assertThat(result.path("lane").path("latestRevision").asLong()).isEqualTo(3);
        assertThat(Instant.parse(result.path("lane").path("latestEffectiveAt").asString())).isEqualTo(currentAt);
        var evidence=result.path("cancelledRevisions");
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).path("revision").asLong()).isEqualTo(2);
        assertThat(evidence.get(1).path("revision").asLong()).isEqualTo(3);
        for(var item:evidence) {
            assertThat(item.path("revisionId").asString()).isNotBlank();
            assertThat(item.path("cancelledByMembershipId").asString()).isEqualTo(membership(f.orgA,f.ownerA.id).toString());
            assertThat(item.path("reason").asString()).isEqualTo(REASON);
            assertThat(Instant.parse(item.path("cancelledAt").asString())).isAfter(currentAt);
        }
        var correlation=cancelled.headers().firstValue("X-Correlation-ID").orElseThrow();
        var audit=jdbc.sql("SELECT reason,correlation_id,changes::text FROM stockrescue.audit_entry WHERE org_id=:org AND resource_id=:lane AND action='LANE_SCHEDULED_REVISIONS_CANCELLED'")
                .param("org",f.orgA).param("lane",id).query((row,index)->List.of(row.getString("reason"),row.getString("correlation_id"),row.getString("changes"))).single();
        assertThat(audit.get(0)).isEqualTo(REASON);
        assertThat(audit.get(1)).isEqualTo(correlation);
        assertThat(audit.get(2)).contains(evidence.get(0).path("revisionId").asString(),evidence.get(1).path("revisionId").asString());

        problem(owner.write("POST",cancelPath(f.orgA,id),cancellation(4,3)),409,"NO_FUTURE_REVISIONS");
        var historyPath=lanePath+"/revisions";
        var history=tree(owner.get(historyPath)).path("items");
        assertThat(history).hasSize(3);
        assertThat(history.get(0).path("cancellation").path("revision").asLong()).isEqualTo(3);
        assertThat(history.get(0).path("cancellation").path("reason").asString()).isEqualTo(REASON);
        assertThat(history.get(0).path("validUntil").isNull()).isTrue();
        assertThat(history.get(1).path("cancellation").path("revision").asLong()).isEqualTo(2);
        assertThat(history.get(1).path("validUntil").isNull()).isTrue();
        assertThat(history.get(2).path("cancellation").isNull()).isTrue();
        assertThat(history.get(2).path("validUntil").isNull()).isTrue();

        var changed=owner.write("PATCH",lanePath,laneUpdate(4,newOrigin,newDestination,true));
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(tree(changed).path("status").asString()).isEqualTo("UNCONFIGURED");
        assertThat(tree(changed).path("missing").toString()).contains("COST_ZONE_MISMATCH");
        assertThat(tree(changed).path("currentCost").isNull()).isTrue();

        doCallRealMethod().when(clock).instant();
        var replacement=owner.write("POST",lanePath+"/revisions",revision(5,3,"16.0000","EUR",null));
        assertThat(replacement.statusCode()).as(replacement.body()).isEqualTo(201);
        assertThat(tree(replacement).path("status").asString()).isEqualTo("CONFIGURED");
        assertThat(tree(replacement).path("latestRevision").asLong()).isEqualTo(4);
        var replacementAt=Instant.parse(tree(replacement).path("currentCost").path("effectiveAt").asString());
        assertThat(tree(replacement).path("currentCost").path("costAmount").asString()).isEqualTo("16.0000");

        doReturn(tomorrow).when(clock).instant();
        assertThat(tree(owner.get(lanePath)).path("currentCost").path("costAmount").asString()).isEqualTo("16.0000");
        doReturn(later.plusSeconds(1)).when(clock).instant();
        assertThat(tree(owner.get(lanePath)).path("currentCost").path("costAmount").asString()).isEqualTo("16.0000");
        var reopened=new Browser(f.ownerA);
        var persisted=tree(reopened.get(historyPath)).path("items");
        assertThat(persisted.get(0).path("cancellation").isNull()).isTrue();
        assertThat(persisted.get(1).path("cancellation").path("cancelledAt").asString()).isNotBlank();
        assertThat(persisted.get(2).path("cancellation").path("cancelledAt").asString()).isNotBlank();
        assertThat(Instant.parse(persisted.get(3).path("validUntil").asString())).isEqualTo(replacementAt);
    }

    @Test
    void cancellationRequiresCurrentOrganizationAuthorityAndActiveLaneStores() throws Exception {
        var laneA=createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);var idA=UUID.fromString(laneA.path("id").asString());
        var laneB=createLane(f.ownerB,f.orgB,f.b1,f.b2,f.zb1,f.zb2);var idB=UUID.fromString(laneB.path("id").asString());
        var owner=f.ownerA.browser();var now=Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);doReturn(now).when(clock).instant();
        assertThat(owner.write("POST",lanes(f.orgA)+"/"+idA+"/revisions",revision(0,0,"12.0000","EUR",null)).statusCode()).isEqualTo(201);
        assertThat(owner.write("POST",lanes(f.orgA)+"/"+idA+"/revisions",revision(1,1,"13.0000","EUR",now.plus(1,ChronoUnit.DAYS))).statusCode()).isEqualTo(201);
        var path=cancelPath(f.orgA,idA);var request=cancellation(2,2);
        problem(owner.send("POST",path,json.writeValueAsString(request),null),403,"CSRF_INVALID");
        problem(f.manager.browser().write("POST",path,request),403,"ACCESS_DENIED");
        problem(f.ownerB.browser().write("POST",path,request),404,"RESOURCE_NOT_FOUND");
        problem(owner.write("POST",cancelPath(f.orgA,idB),request),404,"RESOURCE_NOT_FOUND");
        problem(owner.write("POST",cancelPath(f.orgA,UUID.randomUUID()),request),404,"RESOURCE_NOT_FOUND");

        var revokedAdmin=f.user();f.member(revokedAdmin,f.orgA,"ORG_ADMIN",null);var revokedSession=revokedAdmin.browser();
        var revokedMembership=membership(f.orgA,revokedAdmin.id);
        var revoked=owner.write("DELETE","/api/v1/organizations/"+f.orgA+"/members/"+revokedMembership,version(0));
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        problem(revokedSession.write("POST",path,request),404,"RESOURCE_NOT_FOUND");

        assertThat(owner.write("POST","/api/v1/organizations/"+f.orgA+"/stores/"+f.a1+"/archive",version(0)).statusCode()).isEqualTo(200);
        problem(owner.write("POST",path,request),404,"RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.lane_revision_cancellation WHERE org_id=:org AND lane_id=:lane")
                .param("org",f.orgA).param("lane",idA).query(Long.class).single()).isZero();
        assertThat(tree(owner.get(lanes(f.orgA)+"/"+idA)).path("version").asLong()).isEqualTo(2);
    }

    @Test
    void cancellationAuditFailureLeavesSchedulesAndLaneVersionUntouched() throws Exception {
        var lane=createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);var id=UUID.fromString(lane.path("id").asString());var owner=f.ownerA.browser();
        var current=owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(0,0,"12.0000","EUR",null));
        assertThat(current.statusCode()).as(current.body()).isEqualTo(201);
        var now=Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);doReturn(now).when(clock).instant();
        assertThat(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(1,1,"13.0000","EUR",now.plus(1,ChronoUnit.DAYS))).statusCode()).isEqualTo(201);
        doThrow(new IllegalStateException("Test audit failure")).when(audit).securityChange(any(),eq(f.orgA),eq("transport-lane"),eq(id),
                eq("LANE_SCHEDULED_REVISIONS_CANCELLED"),anyMap(),eq(REASON));
        problem(owner.write("POST",cancelPath(f.orgA,id),cancellation(2,2)),500,"INTERNAL_ERROR");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.lane_revision_cancellation WHERE org_id=:org AND lane_id=:lane")
                .param("org",f.orgA).param("lane",id).query(Long.class).single()).isZero();
        assertThat(tree(owner.get(lanes(f.orgA)+"/"+id)).path("version").asLong()).isEqualTo(2);
        assertThat(tree(owner.get(lanes(f.orgA)+"/"+id+"/revisions")).path("items").get(0).path("cancellation").isNull()).isTrue();
    }

    @Test
    void concurrentLaneCommandsSerializeCancellationAgainstZoneAndCostChanges() throws Exception {
        var lane=createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);var id=UUID.fromString(lane.path("id").asString());var owner=f.ownerA.browser();
        var current=owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(0,0,"12.0000","EUR",null));
        assertThat(current.statusCode()).as(current.body()).isEqualTo(201);
        var now=Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);doReturn(now).when(clock).instant();
        var tomorrow=now.plus(1,ChronoUnit.DAYS);
        assertThat(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(1,1,"13.0000","EUR",tomorrow)).statusCode()).isEqualTo(201);
        var newOrigin=f.zone(f.orgA,f.a1,"Concurrent origin","AMBIENT");var newDestination=f.zone(f.orgA,f.a2,"Concurrent destination","AMBIENT");
        var second=new Browser(f.ownerA);var lanePath=lanes(f.orgA)+"/"+id;
        var zoneRace=concurrent(()->owner.write("POST",cancelPath(f.orgA,id),cancellation(2,2)),
                ()->second.write("PATCH",lanePath,laneUpdate(2,newOrigin,newDestination,true)));
        assertThat(zoneRace.getFirst().statusCode()).isEqualTo(200);
        assertThat(zoneRace.get(1).statusCode()).isEqualTo(409);
        assertThat(tree(zoneRace.get(1)).path("code").asString()).isIn("SCHEDULED_REVISIONS_PENDING","CONFIGURATION_CONFLICT");
        assertThat(tree(owner.get(lanePath)).path("version").asLong()).isEqualTo(3);
        assertThat(tree(owner.get(lanePath)).path("originZoneId").asString()).isEqualTo(f.z1.toString());

        var later=tomorrow.plus(1,ChronoUnit.HOURS);
        var costRace=concurrent(()->owner.write("POST",cancelPath(f.orgA,id),cancellation(3,2)),
                ()->second.write("POST",lanePath+"/revisions",revision(3,2,"14.0000","EUR",later)));
        assertThat(costRace.stream().filter(response->response.statusCode()<300)).hasSize(1);
        assertThat(costRace.stream().filter(response->response.statusCode()>=400).map(response->response.statusCode()).toList()).containsExactly(409);
        var finalLane=tree(owner.get(lanePath));
        assertThat(finalLane.path("status").asString()).isEqualTo("CONFIGURED");
        assertThat(finalLane.path("currentCost").path("costAmount").asString()).isEqualTo("12.0000");
        assertThat(finalLane.path("latestRevision").asLong()).isIn(2L,3L);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.lane_revision_cancellation WHERE org_id=:org AND lane_id=:lane")
                .param("org",f.orgA).param("lane",id).query(Long.class).single()).isIn(0L,1L);
    }

    @Test
    void simultaneousCostAndZoneChangesReturnOneConflictWithoutOverwritingEvidence() throws Exception {
        var lane=createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);var id=UUID.fromString(lane.path("id").asString());
        var first=f.ownerA.browser();var second=new Browser(f.ownerA);
        var prices=concurrent(()->first.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(0,0,"1.1111","EUR",null)),
                ()->second.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(0,0,"2.2222","EUR",null)));
        assertThat(prices).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201,409);
        assertThat(tree(first.get(lanes(f.orgA)+"/"+id+"/revisions")).path("items")).hasSize(1);
        var laneAndPrice=concurrent(()->first.write("PATCH",lanes(f.orgA)+"/"+id,laneUpdate(1,f.z1,f.z2,false)),
                ()->second.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(1,1,"3.3333","EUR",null)));
        assertThat(laneAndPrice).extracting(HttpResponse::statusCode).containsAnyOf(200,201).contains(409);
        assertThat(laneAndPrice.stream().filter(response->response.statusCode()<300)).hasSize(1);
        assertThat(tree(first.get(lanes(f.orgA)+"/"+id)).path("version").asLong()).isEqualTo(2);
        var names=concurrent(()->first.write("PATCH",zones(f.orgA,f.a1)+"/"+f.z1,Map.of("name","First zone name","version",0,"reason",REASON)),
                ()->second.write("PATCH",zones(f.orgA,f.a1)+"/"+f.z1,Map.of("name","Second zone name","version",0,"reason",REASON)));
        assertThat(names).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200,409);
    }

    @Test
    void changedOrInactiveZonesCannotReuseCostEvidenceAndInactiveStoresBlockNewRevisions() throws Exception {
        var lane=createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);var id=UUID.fromString(lane.path("id").asString());var owner=f.ownerA.browser();
        assertThat(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(0,0,"8.0001","EUR",null)).statusCode()).isEqualTo(201);
        assertThat(owner.write("POST",zones(f.orgA,f.a1)+"/"+f.z1+"/archive",version(0)).statusCode()).isEqualTo(200);
        var inactive=tree(owner.get(lanes(f.orgA)+"/"+id));
        assertThat(inactive.path("status").asString()).isEqualTo("INACTIVE");assertThat(inactive.path("currentCost").isNull()).isTrue();
        problem(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(1,1,"9.0000","EUR",null)),409,"CONFIGURATION_INACTIVE");
        var freshA=f.zone(f.orgA,f.a1,"New origin","AMBIENT");var freshB=f.zone(f.orgA,f.a2,"New destination","AMBIENT");
        var changed=owner.write("PATCH",lanes(f.orgA)+"/"+id,laneUpdate(1,freshA,freshB,true));
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(tree(changed).path("missing").toString()).contains("COST_ZONE_MISMATCH");
        assertThat(tree(changed).path("currentCost").isNull()).isTrue();
        assertThat(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(2,1,"9.0000","EUR",null)).statusCode()).isEqualTo(201);
        var history=tree(owner.get(lanes(f.orgA)+"/"+id+"/revisions")).path("items");
        assertThat(history.get(1).path("originZoneId").asString()).isEqualTo(f.z1.toString());
        assertThat(owner.write("POST","/api/v1/organizations/"+f.orgA+"/stores/"+f.a2+"/archive",version(0)).statusCode()).isEqualTo(200);
        assertThat(tree(owner.get(lanes(f.orgA)+"/"+id)).path("missing").toString()).contains("INACTIVE_STORE");
        problem(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(3,2,"10.0000","EUR",null)),404,"RESOURCE_NOT_FOUND");
        assertThat(tree(owner.get(lanes(f.orgA)+"/"+id+"/revisions")).path("items")).isEqualTo(history);
    }

    @Test
    void auditFailuresRollBackZoneLaneAndCostChangesTogetherWithVersions() throws Exception {
        var owner=f.ownerA.browser();
        doThrow(new IllegalStateException("Test audit failure")).when(audit).securityChange(any(),eq(f.orgA),any(),any(),anyString(),anyMap(),anyString());
        problem(owner.write("POST",zones(f.orgA,f.a1),Map.of("name","Rolled back","temperatureClass","FROZEN")),500,"INTERNAL_ERROR");
        problem(owner.write("POST",lanes(f.orgA),laneBody(f.a1,f.a2,f.z1,f.z2)),500,"INTERNAL_ERROR");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.transport_lane WHERE org_id=:org").param("org",f.orgA).query(Long.class).single()).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.orgA),any(),any(),anyString(),anyMap(),anyString());
        var lane=createLane(f.ownerA,f.orgA,f.a1,f.a2,f.z1,f.z2);var id=UUID.fromString(lane.path("id").asString());
        doThrow(new IllegalStateException("Test revision audit failure")).when(audit).securityChange(any(),eq(f.orgA),any(),any(),eq("LANE_COST_REVISED"),anyMap(),anyString());
        problem(owner.write("POST",lanes(f.orgA)+"/"+id+"/revisions",revision(0,0,"10.2345","EUR",null)),500,"INTERNAL_ERROR");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.lane_revision WHERE lane_id=:id").param("id",id).query(Long.class).single()).isZero();
        assertThat(tree(owner.get(lanes(f.orgA)+"/"+id)).path("version").asLong()).isZero();
    }

    @Test
    void csrfRoleAndInactiveStoreGuardsApplyToConfigurationCommands() throws Exception {
        var owner=f.ownerA.browser();
        problem(owner.send("POST",lanes(f.orgA),json.writeValueAsString(laneBody(f.a1,f.a2,f.z1,f.z2)),null),403,"CSRF_INVALID");
        problem(f.manager.browser().write("POST",lanes(f.orgA),laneBody(f.a1,f.a2,f.z1,f.z2)),403,"ACCESS_DENIED");
        var forged=laneBody(f.a1,f.a2,f.z1,f.z2);forged.put("orgId",f.orgB);
        problem(owner.write("POST",lanes(f.orgA),forged),400,"INVALID_REQUEST");
        var invalidReason=laneBody(f.a1,f.a2,f.z1,f.z2);invalidReason.put("reason","Invalid\u0000reason");
        problem(owner.write("POST",lanes(f.orgA),invalidReason),400,"INVALID_REQUEST");
        problem(owner.write("PATCH",zones(f.orgA,f.a1)+"/"+f.z1,Map.of("name","Rejected rename","version",0,"reason","Invalid\u0000reason")),400,"INVALID_REQUEST");
        assertThat(owner.write("POST","/api/v1/organizations/"+f.orgA+"/stores/"+f.a1+"/archive",version(0)).statusCode()).isEqualTo(200);
        problem(owner.write("POST",zones(f.orgA,f.a1),Map.of("name","Inactive store","temperatureClass","AMBIENT")),404,"RESOURCE_NOT_FOUND");
        assertThat(tree(owner.get(zones(f.orgA,f.a1))).path("canManage").asBoolean()).isFalse();
    }

    private JsonNode createLane(User owner,UUID org,UUID origin,UUID destination,UUID a,UUID b) throws Exception {
        var response=owner.browser().write("POST",lanes(org),laneBody(origin,destination,a,b));assertThat(response.statusCode()).as(response.body()).isEqualTo(201);return tree(response);
    }
    private UUID membership(UUID org,UUID user) {
        return jdbc.sql("SELECT id FROM stockrescue.membership WHERE org_id=:org AND user_id=:user AND state='ACTIVE'")
                .param("org",org).param("user",user).query(UUID.class).single();
    }
    private Map<String,Object> laneBody(UUID a,UUID b,UUID za,UUID zb) { return new HashMap<>(Map.of("originStoreId",a,"destinationStoreId",b,"originZoneId",za,"destinationZoneId",zb,"reason",REASON)); }
    private Map<String,Object> laneUpdate(long version,UUID a,UUID b,boolean active) { return Map.of("version",version,"originZoneId",a,"destinationZoneId",b,"active",active,"reason",REASON); }
    private Map<String,Object> revision(long version,long previous,String amount,String currency,Instant effective) {
        var body=new HashMap<String,Object>(Map.of("laneVersion",version,"expectedRevision",previous,"costAmount",amount,"currencyCode",currency,"durationMinutes",45,"reason",REASON));
        if(effective!=null) body.put("effectiveAt",effective.toString());return body;
    }
    private Map<String,Object> cancellation(long version,long latestRevision) { return Map.of("laneVersion",version,"expectedRevision",latestRevision,"reason",REASON); }
    private Map<String,Object> version(long version) { return Map.of("version",version,"reason",REASON); }
    private String zones(UUID org,UUID store) { return "/api/v1/organizations/"+org+"/stores/"+store+"/storage-zones"; }
    private String lanes(UUID org) { return "/api/v1/organizations/"+org+"/transport-lanes"; }
    private String cancelPath(UUID org,UUID lane) { return lanes(org)+"/"+lane+"/scheduled-revisions/cancel"; }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private void problem(HttpResponse<String> response,int status,String code) { assertThat(response.statusCode()).as(response.body()).isEqualTo(status);assertThat(tree(response).path("code").asString()).isEqualTo(code); }
    private List<HttpResponse<String>> concurrent(java.util.concurrent.Callable<HttpResponse<String>> one,java.util.concurrent.Callable<HttpResponse<String>> two) throws Exception {
        var start=new CountDownLatch(1);try(var workers=Executors.newFixedThreadPool(2)) { var a=workers.submit(()->{start.await();return one.call();});var b=workers.submit(()->{start.await();return two.call();});start.countDown();return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)); }
    }
    private class Fixture {
        final User ownerA=user(),ownerB=user(),manager=user(),reader=user();
        final UUID orgA=org(ownerA),orgB=org(ownerB);
        final UUID a1=store(orgA,"NORTH"),a2=store(orgA,"SOUTH"),a3=store(orgA,"EAST"),a4=store(orgA,"WEST"),b1=store(orgB,"FOREIGN1"),b2=store(orgB,"FOREIGN2");
        final UUID z1=zone(orgA,a1,"Primary","AMBIENT"),z2=zone(orgA,a2,"Primary","AMBIENT"),chilled2=zone(orgA,a2,"Cold room","CHILLED"),zb1=zone(orgB,b1,"Primary","AMBIENT"),zb2=zone(orgB,b2,"Primary","AMBIENT");
        Fixture() { member(ownerA,orgA,"ORG_ADMIN",null);member(ownerB,orgB,"ORG_ADMIN",null);member(manager,orgA,"MEMBER","STORE_MANAGER");member(reader,orgA,"MEMBER","READ_ONLY_ANALYST"); }
        User user() { var user=new User(UUID.randomUUID(),UUID.randomUUID()+"@example.test");jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)").param("id",user.id).param("email",user.email).update();jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)").param("id",user.id).param("hash",hash).update();return user; }
        UUID org(User owner) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Operational settings cooperative',:owner)").param("id",id).param("owner",owner.id).update();return id; }
        UUID store(UUID org,String code) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:id,:org,:code,:name,'Europe/Warsaw')").param("id",id).param("org",org).param("code",code).param("name",code+" store").update();return id; }
        UUID zone(UUID org,UUID store,String name,String temperature) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES (:id,:org,:store,:name,:temperature)").param("id",id).param("org",org).param("store",store).param("name",name).param("temperature",temperature).update();return id; }
        void member(User user,UUID org,String role,String grant) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:user,:role)").param("id",id).param("org",org).param("user",user.id).param("role",role).update();if(grant!=null) jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:member,:store,:role)").param("id",UUID.randomUUID()).param("org",org).param("member",id).param("store",a1).param("role",grant).update(); }
    }
    private class User { final UUID id;final String email;private Browser browser;User(UUID id,String email){this.id=id;this.email=email;}Browser browser()throws Exception{if(browser==null)browser=new Browser(this);return browser;} }
    private class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        Browser(User user)throws Exception{assertThat(write("POST","/api/v1/auth/sign-in",Map.of("email",user.email,"password",PASSWORD)).statusCode()).isEqualTo(200);}
        HttpResponse<String> get(String path)throws Exception{return send("GET",path,"",null);}
        HttpResponse<String> write(String method,String path,Object body)throws Exception{var token=tree(get("/api/v1/auth/csrf")).path("token").asString();return send(method,path,json.writeValueAsString(body),token);}
        HttpResponse<String> send(String method,String path,String body,String token)throws Exception{var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");if(token!=null)request.header("X-CSRF-TOKEN",token);return client.send(request.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    }
}
