package com.stockrescue.catalog.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LotTraceabilityTest extends InfrastructureTestBase {
    private static final String PASSWORD="lot-traceability-test-passphrase";
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private AuditWriter audit;
    private String hash;
    private Fixture f;
    @BeforeAll void credentials() { hash=encoder.encode(PASSWORD); }
    @BeforeEach void fixture() throws Exception { f=new Fixture(); }

    @Test
    void oneConfirmedLotHasIndependentSourceRecordsWithExplicitUnknownSupplierData() throws Exception {
        var lot=register(lotBody(UUID.randomUUID(),"VERIFIED",true));
        var saved=tree(f.ownerA.browser().get(lot(lot)));
        assertThat(saved.path("identityState").asString()).isEqualTo("CONFIRMED");
        assertThat(saved.path("confirmedByMembershipId").asString()).isEqualTo(f.membership(f.ownerA).toString());
        assertThat(saved.path("confirmedAt").isNull()).isFalse();
        var one=source(lot,sourceBody(UUID.randomUUID(),f.a1,"SAME-SUPPLIER-LOT"));
        var two=source(lot,sourceBody(UUID.randomUUID(),f.a1,"SAME-SUPPLIER-LOT"));
        assertThat(one).isNotEqualTo(two);
        source(lot,sourceBody(UUID.randomUUID(),null,null));
        var trace=tree(f.ownerA.browser().get(sources(lot)));
        assertThat(trace.path("items")).hasSize(3);
        assertThat(trace.path("coverage").asString()).isEqualTo("RECORDED_EVIDENCE_ONLY");
        assertThat(trace.path("visibilityScope").asString()).isEqualTo("ALL_ORGANIZATION_SOURCES");
        assertThat(trace.path("items")).anySatisfy(row -> { assertThat(row.path("supplierName").isNull()).isTrue(); assertThat(row.path("supplierReference").isNull()).isTrue(); });
        assertThat(trace.toString()).doesNotContain("quantity","receivedAt","StockPosition","ALLOWED");
        assertThat(count("consignment_evidence")).isEqualTo(3);
        assertThat(audits("SOURCE_EVIDENCE_REGISTERED")).isEqualTo(3);
    }

    @Test
    void unknownManufacturerClaimsStaySeparateAndConfirmedCollisionsRequireExplicitResolution() throws Exception {
        var one=register(lotBody(UUID.randomUUID(),"REUSED-CODE",false));
        var otherSource=lotBody(UUID.randomUUID(),"REUSED-CODE",false); otherSource.put("manufacturerProvenance",provenance("another-unknown-producer"));
        var two=register(otherSource);
        assertThat(one).isNotEqualTo(two);
        var confirmed=lotBody(UUID.randomUUID(),"KNOWN",true);
        register(confirmed);
        var duplicate=new HashMap<>(confirmed); duplicate.put("requestId",UUID.randomUUID());
        problem(f.ownerA.browser().write(lots(),duplicate),409,"CATALOG_CONFLICT");
        confirmed.put("requestId",UUID.randomUUID()); confirmed.put("lotCode","NO-REVIEW"); confirmed.put("manufacturerVerified",false);
        problem(f.ownerA.browser().write(lots(),confirmed),422,"INVALID_REQUEST");
        assertThat(count("manufacturer_lot")).isEqualTo(3);
        assertThat(count("lot_registration")).isEqualTo(3);
    }

    @Test
    void assignedStoreManagerRegistersOnlyUnverifiedMetadataAndRetriesCannotCrossStoreOrActor() throws Exception {
        var manager=f.manager.browser();
        var command=lotBody(UUID.randomUUID(),"STORE-CANDIDATE",false); command.put("storeId",f.a2);
        var created=manager.write(lots(),command); assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var id=UUID.fromString(tree(created).path("id").asString());
        assertThat(tree(created).path("identityState").asString()).isEqualTo("CANDIDATE");
        assertThat(tree(created).path("manufacturerIdentityKey").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT store_id=:store AND actor_user_id=:actor AND recorded_at IS NOT NULL FROM stockrescue.lot_registration WHERE org_id=:org AND request_id=:request")
                .param("store",f.a2).param("actor",f.manager.id).param("org",f.orgA).param("request",command.get("requestId"))
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.lot_registration WHERE org_id=:org AND lot_id=:lot AND actor_user_id=:actor")
                .param("org",f.orgA).param("lot",id).param("actor",f.manager.id).query(Long.class).single()).isEqualTo(1);
        assertThat(manager.write(lots(),command).statusCode()).isEqualTo(200);

        var changedPayload=new HashMap<>(command); changedPayload.put("manufacturerProvenance",provenance("different-source"));
        problem(manager.write(lots(),changedPayload),409,"CATALOG_CONFLICT");
        var anotherStore=new HashMap<>(command); anotherStore.put("storeId",f.a1);
        problem(manager.write(lots(),anotherStore),404,"RESOURCE_NOT_FOUND");
        jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:member,:store,'STORE_MANAGER')")
                .param("id",UUID.randomUUID()).param("org",f.orgA).param("member",f.membership(f.manager)).param("store",f.a1).update();
        problem(manager.write(lots(),anotherStore),409,"CATALOG_CONFLICT");
        problem(f.ownerA.browser().write(lots(),command),409,"CATALOG_CONFLICT");

        var missingStore=lotBody(UUID.randomUUID(),"NO-STORE",false);
        problem(manager.write(lots(),missingStore),403,"ACCESS_DENIED");
        var confirmed=lotBody(UUID.randomUUID(),"NO-CONFIRMATION",true); confirmed.put("storeId",f.a2);
        problem(manager.write(lots(),confirmed),403,"ACCESS_DENIED");
        var correction=Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Attempted manager identity confirmation",
                "evidenceReviewed",true,"identityState","CONFIRMED","manufacturerIdentityKey","registry:unreviewed",
                "manufacturerProvenance",provenance("unreviewed-source"),"manufacturerVerified",true,
                "dateMarking",Map.of("type","USE_BY","precision","UNKNOWN"));
        problem(manager.write(lot(id)+"/corrections",correction),403,"ACCESS_DENIED");
        var safetyEvidence=Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Attempted status change",
                "recallState","NOT_REPORTED","damageState","NOT_REPORTED","provenance",provenance("unreviewed-notice"));
        problem(manager.write(lot(id)+"/safety-status-evidence",safetyEvidence),403,"ACCESS_DENIED");
        var operatorCommand=lotBody(UUID.randomUUID(),"OPERATOR-DENIED",false); operatorCommand.put("storeId",f.a1);
        problem(f.operator.browser().write(lots(),operatorCommand),403,"ACCESS_DENIED");
        var analystCommand=lotBody(UUID.randomUUID(),"ANALYST-DENIED",false); analystCommand.put("storeId",f.a1);
        problem(f.analyst.browser().write(lots(),analystCommand),403,"ACCESS_DENIED");
        assertThat(count("manufacturer_lot")).isEqualTo(1); assertThat(count("lot_registration")).isEqualTo(1);
        assertThat(count("lot_correction")).isZero();
        assertThat(audits("LOT_REGISTERED")).isEqualTo(1);
    }

    @Test
    void archivedStoreRejectsNewRegistrationsButPreservesAnExactAdministratorRetry() throws Exception {
        var owner=f.ownerA.browser();
        var command=lotBody(UUID.randomUUID(),"ARCHIVED-STORE-RETRY",false); command.put("storeId",f.a2);
        var first=owner.write(lots(),command); assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        var id=tree(first).path("id").asString();
        assertThat(owner.write("/api/v1/organizations/"+f.orgA+"/stores/"+f.a2+"/archive",Map.of("version",0,"reason","Store closed")).statusCode()).isEqualTo(200);
        var retry=owner.write(lots(),command);
        assertThat(retry.statusCode()).isEqualTo(200); assertThat(tree(retry).path("id").asString()).isEqualTo(id);
        var newCommand=lotBody(UUID.randomUUID(),"NEW-AFTER-ARCHIVE",false); newCommand.put("storeId",f.a2);
        problem(owner.write(lots(),newCommand),409,"CATALOG_INACTIVE");
        assertThat(count("manufacturer_lot")).isEqualTo(1); assertThat(count("lot_registration")).isEqualTo(1);
    }

    @Test void aRevokedStoreGrantRejectsTheWaitingLotRegistration() throws Exception {
        orderedLotRegistration(false,"STORE_GRANT_REVOKED",() -> f.ownerA.browser().send("DELETE",
                "/api/v1/organizations/"+f.orgA+"/members/"+f.membership(f.manager)+"/store-grants/"+f.a2,
                Map.of("memberVersion",0,"grantVersion",0,"reason","Store assignment ended"),f.ownerA.browser().token()));
    }

    @Test void committedLotRegistrationCompletesBeforeWaitingStoreGrantRevocation() throws Exception {
        orderedLotRegistration(true,"LOT_REGISTERED",() -> f.ownerA.browser().send("DELETE",
                "/api/v1/organizations/"+f.orgA+"/members/"+f.membership(f.manager)+"/store-grants/"+f.a2,
                Map.of("memberVersion",0,"grantVersion",0,"reason","Store assignment ended"),f.ownerA.browser().token()));
    }

    @Test void storeArchivalRejectsTheWaitingLotRegistration() throws Exception {
        orderedLotRegistration(false,"STORE_ARCHIVED",() -> f.ownerA.browser().write("/api/v1/organizations/"+f.orgA+"/stores/"+f.a2+"/archive",
                Map.of("version",0,"reason","Store closed")));
    }

    @Test void productArchivalRejectsTheWaitingLotRegistration() throws Exception {
        orderedLotRegistration(false,"PRODUCT_ARCHIVED",() -> f.ownerA.browser().write(products()+"/"+f.productA+"/archive",
                Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Product discontinued")));
    }

    @Test void committedLotRegistrationCompletesBeforeWaitingProductArchival() throws Exception {
        orderedLotRegistration(true,"LOT_REGISTERED",() -> f.ownerA.browser().write(products()+"/"+f.productA+"/archive",
                Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Product discontinued")));
    }

    private void orderedLotRegistration(boolean registrationFirst,String blockedAction,Callable<HttpResponse<String>> closingCommand) throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); if (!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Command barrier timed out"); return call.callRealMethod(); })
                .when(audit).securityChange(any(),eq(f.orgA),anyString(),any(),eq(blockedAction),anyMap(),anyString());
        var request=lotBody(UUID.randomUUID(),"RACE-"+UUID.randomUUID(),false); request.put("storeId",f.a2);
        var manager=f.manager.browser();
        try (var workers=Executors.newFixedThreadPool(2)) {
            Callable<HttpResponse<String>> registration=() -> manager.write(lots(),request);
            if (registrationFirst) {
                var saving=workers.submit(registration);
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                var waitingClose=workers.submit(closingCommand);
                awaitLockWait(); release.countDown();
                assertThat(saving.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(201);
                assertThat(waitingClose.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            } else {
                var closing=workers.submit(closingCommand);
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                var waitingRegistration=workers.submit(registration);
                awaitLockWait(); release.countDown();
                assertThat(closing.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertThat(waitingRegistration.get(20,TimeUnit.SECONDS).statusCode()).isIn(404,409);
            }
        } finally { release.countDown(); }
        assertThat(count("manufacturer_lot")).isEqualTo(registrationFirst?1:0);
        assertThat(count("lot_registration")).isEqualTo(registrationFirst?1:0);
    }

    private void awaitLockWait() {
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'")
                .query(Long.class).single()).isPositive());
    }

    @Test
    void originalLabelsKeepInvalidAndIncompleteDateEvidenceDistinct() throws Exception {
        for (var value:new String[][]{{"2027-04-30","DAY","USE_BY","VALID"},{" 2027-02 ","MONTH","BEST_BEFORE_END","VALID"},
                {"2027","YEAR","BEST_BEFORE_END","VALID"},{"2027-12-15","DAY","BEST_BEFORE_END","INCOMPLETE"},
                {"2027-02","MONTH","USE_BY","INCOMPLETE"},
                {"2027-02-31","DAY","USE_BY","INVALID"},{"01/02/2027","DAY","USE_BY","INVALID"},
                {null,"UNKNOWN","UNKNOWN","INCOMPLETE"}}) {
            var body=lotBody(UUID.randomUUID(),"DATE",false);
            var marking=new HashMap<String,Object>(Map.of("type",value[2],"precision",value[1],"provenance",provenance("label-photo")));
            if (value[0]!=null) marking.put("labeledValue",value[0]);
            body.put("dateMarking",marking);
            var id=register(body);
            var date=tree(f.ownerA.browser().get(lot(id))).path("dateMarking");
            if (value[0]==null) assertThat(date.path("labeledValue").isNull()).isTrue();
            else assertThat(date.path("labeledValue").asString()).isEqualTo(value[0]);
            assertThat(date.path("precision").asString()).isEqualTo(value[1]);
            assertThat(date.path("type").asString()).isEqualTo(value[2]);
            assertThat(date.path("validationStatus").asString()).isEqualTo(value[3]);
            if (value[2].equals("BEST_BEFORE_END") && value[1].equals("DAY"))
                assertThat(date.path("validationReasons").toString()).contains("BEST_BEFORE_END_REQUIRES_PERIOD");
            assertThat(date.path("normalizedCutoff").isNull()).isTrue();
        }
        var malformed=lotBody(UUID.randomUUID(),"INPUT",false);
        malformed.put("dateMarking",Map.of("type","USE_BY","precision","WEEK"));
        problem(f.ownerA.browser().write(lots(),malformed),400,"INVALID_REQUEST");
        var legacy=UUID.randomUUID();
        jdbc.sql("INSERT INTO stockrescue.manufacturer_lot(id,org_id,product_id,lot_code) VALUES (:id,:org,:product,'LEGACY-UNKNOWN')")
                .param("id",legacy).param("org",f.orgA).param("product",f.productA).update();
        var unknown=tree(f.ownerA.browser().get(lot(legacy)));
        assertThat(unknown.path("manufacturerProvenance").isNull()).isTrue();
        assertThat(unknown.path("dateMarking").path("validationStatus").asString()).isEqualTo("INCOMPLETE");

        var unreferenced=lotBody(UUID.randomUUID(),"NO-LABEL-REFERENCE",false);
        unreferenced.put("dateMarking",Map.of("labeledValue","2027-04-30","type","USE_BY","precision","DAY",
                "provenance",Map.of("source","Package photo")));
        var unreferencedId=register(unreferenced);
        var date=tree(f.ownerA.browser().get(lot(unreferencedId))).path("dateMarking");
        assertThat(date.path("validationStatus").asString()).isEqualTo("INCOMPLETE");
        assertThat(date.path("validationReasons").toString()).contains("MISSING_REFERENCE");
    }

    @Test
    void concurrentRegistrationAndExplicitReplayCommitOnlyOneOutcomePerRequest() throws Exception {
        var owner=f.ownerA.browser(); var second=new Browser(f.ownerA);
        var body=lotBody(UUID.randomUUID(),"RETRY",false);
        var results=concurrent(() -> owner.write(lots(),body),() -> second.write(lots(),body));
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,200);
        assertThat(tree(results.get(0)).path("id")).isEqualTo(tree(results.get(1)).path("id"));
        var id=UUID.fromString(tree(results.get(0)).path("id").asString());
        assertThat(owner.write(lots(),body).statusCode()).isEqualTo(200);
        body.put("lotCode","CHANGED"); problem(owner.write(lots(),body),409,"CATALOG_CONFLICT");
        var observed=sourceBody(UUID.randomUUID(),null,"SHARED-REFERENCE");
        var observations=concurrent(() -> owner.write(sources(id),observed),() -> second.write(sources(id),observed));
        assertThat(observations.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,200);
        assertThat(tree(observations.get(0)).path("id")).isEqualTo(tree(observations.get(1)).path("id"));
        assertThat(owner.write(sources(id),observed).statusCode()).isEqualTo(200);
        observed.put("supplierReference","CHANGED"); problem(owner.write(sources(id),observed),409,"CATALOG_CONFLICT");
        assertThat(count("manufacturer_lot")).isEqualTo(1); assertThat(count("lot_registration")).isEqualTo(1);
        assertThat(count("consignment_evidence")).isEqualTo(1); assertThat(count("consignment_registration")).isEqualTo(1);
        assertThat(audits("LOT_REGISTERED")).isEqualTo(1); assertThat(audits("SOURCE_EVIDENCE_REGISTERED")).isEqualTo(1);
    }

    @Test
    void sourceDocumentsAndPaginationAreRestrictedToCurrentAssignedStores() throws Exception {
        var id=register(lotBody(UUID.randomUUID(),"SCOPE",true));
        var first=source(id,sourceBody(UUID.randomUUID(),f.a1,"PRIVATE-NORTH"));
        var second=source(id,sourceBody(UUID.randomUUID(),f.a2,"PRIVATE-SOUTH"));
        var organizational=source(id,sourceBody(UUID.randomUUID(),null,"PRIVATE-ORGANIZATION"));
        var operator=f.operator.browser();
        var visible=tree(operator.get(sources(id)+"?limit=1"));
        assertThat(visible.path("items")).hasSize(1); assertThat(visible.path("hasMore").asBoolean()).isFalse();
        assertThat(visible.path("visibilityScope").asString()).isEqualTo("ASSIGNED_STORE_SOURCES");
        assertThat(visible.toString()).doesNotContain("PRIVATE-SOUTH","PRIVATE-ORGANIZATION",second.toString(),organizational.toString());
        problem(operator.get(sources(id)+"/"+second),404,"RESOURCE_NOT_FOUND");
        problem(operator.get(sources(id)+"/"+organizational),404,"RESOURCE_NOT_FOUND");
        assertThat(tree(f.member.browser().get(sources(id))).path("items")).isEmpty();
        problem(f.analyst.browser().write(sources(id),sourceBody(UUID.randomUUID(),f.a1,"DENIED")),403,"ACCESS_DENIED");
        problem(operator.write(sources(id),sourceBody(UUID.randomUUID(),f.a2,"DENIED")),404,"RESOURCE_NOT_FOUND");
        problem(operator.write(sources(id),sourceBody(UUID.randomUUID(),null,"DENIED")),403,"ACCESS_DENIED");
        var retry=sourceBody(UUID.randomUUID(),f.a1,"OPERATOR");
        assertThat(operator.write(sources(id),retry).statusCode()).isEqualTo(201);
        assertThat(f.manager.browser().write(sources(id),sourceBody(UUID.randomUUID(),f.a2,"MANAGER")).statusCode()).isEqualTo(201);
        jdbc.sql("UPDATE stockrescue.store_grant SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE org_id=:org AND membership_id=:member")
                .param("org",f.orgA).param("member",f.membership(f.operator)).update();
        assertThat(tree(operator.get(sources(id))).path("items")).isEmpty();
        problem(operator.get(sources(id)+"/"+first),404,"RESOURCE_NOT_FOUND");
        problem(operator.write(sources(id),retry),404,"RESOURCE_NOT_FOUND");
        assertThat(tree(f.ownerA.browser().get(sources(id))).path("items")).hasSize(5);
    }

    @Test
    void crossOrganizationIdentifiersCannotRegisterOrExposeLotAndSourceData() throws Exception {
        var id=register(lotBody(UUID.randomUUID(),"OWN",false));
        var bad=lotBody(UUID.randomUUID(),"FOREIGN",false); bad.put("productId",f.productB);
        problem(f.ownerA.browser().write(lots(),bad),404,"RESOURCE_NOT_FOUND");
        problem(f.ownerB.browser().get(lot(id)),404,"RESOURCE_NOT_FOUND");
        problem(f.ownerB.browser().get("/api/v1/organizations/"+f.orgB+"/lots/"+id),404,"RESOURCE_NOT_FOUND");
        problem(f.ownerA.browser().write(sources(id),sourceBody(UUID.randomUUID(),f.b1,"FOREIGN")),404,"RESOURCE_NOT_FOUND");
        problem(f.ownerB.browser().write("/api/v1/organizations/"+f.orgB+"/lots/"+id+"/source-evidence",sourceBody(UUID.randomUUID(),null,"FOREIGN")),404,"RESOURCE_NOT_FOUND");
        problem(f.operator.browser().write(lots(),lotBody(UUID.randomUUID(),"UNAUTHORIZED",false)),403,"ACCESS_DENIED");
        var fake=sourceBody(UUID.randomUUID(),f.a1,"FAKE"); fake.put("receivedQuantity",1);
        problem(f.ownerA.browser().write(sources(id),fake),400,"INVALID_REQUEST");
        assertThat(count("manufacturer_lot")).isEqualTo(1); assertThat(count("lot_registration")).isEqualTo(1);
        assertThat(count("consignment_evidence")).isZero(); assertThat(count("consignment_registration")).isZero();
    }

    @Test
    void archivedProductAndStoreHistoryRemainReadableWhileNewObservationsAreRejected() throws Exception {
        var request=lotBody(UUID.randomUUID(),"ARCHIVE",false); var id=register(request);
        var observed=sourceBody(UUID.randomUUID(),f.a1,"HISTORY"); var source=source(id,observed);
        assertThat(f.ownerA.browser().write(products()+"/"+f.productA+"/archive",Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Product discontinued")).statusCode()).isEqualTo(200);
        problem(f.ownerA.browser().write(lots(),lotBody(UUID.randomUUID(),"NEW",false)),409,"CATALOG_INACTIVE");
        problem(f.ownerA.browser().write(sources(id),sourceBody(UUID.randomUUID(),f.a1,"NEW")),409,"CATALOG_INACTIVE");
        assertThat(f.ownerA.browser().write(lots(),request).statusCode()).isEqualTo(200);
        assertThat(f.ownerA.browser().write(sources(id),observed).statusCode()).isEqualTo(200);
        assertThat(tree(f.ownerA.browser().get(lots()+"?productId="+f.productA)).path("canRegister").asBoolean()).isFalse();
        jdbc.sql("UPDATE stockrescue.store SET active=FALSE,version=version+1,updated_at=clock_timestamp() WHERE id=:id").param("id",f.a1).update();
        problem(f.operator.browser().get(sources(id)+"/"+source),404,"RESOURCE_NOT_FOUND");
        assertThat(f.ownerA.browser().get(sources(id)+"/"+source).statusCode()).isEqualTo(200);
        assertThat(f.ownerA.browser().write(sources(id),observed).statusCode()).isEqualTo(200);
        assertThat(count("consignment_evidence")).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackBothRegistrationRecordsAndTheirDurableOutcomes() throws Exception {
        var body=lotBody(UUID.randomUUID(),"ROLLBACK",false);
        var storeBody=lotBody(UUID.randomUUID(),"STORE-ROLLBACK",false); storeBody.put("storeId",f.a2);
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).securityChange(any(),eq(f.orgA),eq("manufacturer-lot"),any(),anyString(),anyMap(),anyString());
        problem(f.manager.browser().write(lots(),storeBody),500,"INTERNAL_ERROR");
        problem(f.ownerA.browser().write(lots(),body),500,"INTERNAL_ERROR");
        assertThat(count("manufacturer_lot")).isZero(); assertThat(count("lot_registration")).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.orgA),eq("manufacturer-lot"),any(),anyString(),anyMap(),anyString());
        var id=register(body); var observed=sourceBody(UUID.randomUUID(),f.a1,"ROLLBACK");
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).securityChange(any(),eq(f.orgA),eq("consignment-evidence"),any(),anyString(),anyMap(),anyString());
        problem(f.ownerA.browser().write(sources(id),observed),500,"INTERNAL_ERROR");
        assertThat(count("consignment_evidence")).isZero(); assertThat(count("consignment_registration")).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.orgA),eq("consignment-evidence"),any(),anyString(),anyMap(),anyString());
        assertThat(f.ownerA.browser().write(sources(id),observed).statusCode()).isEqualTo(201);
    }

    @Test
    void lotPagesHaveStableOrderingAndValidateProductScope() throws Exception {
        register(lotBody(UUID.randomUUID(),"C",false)); register(lotBody(UUID.randomUUID(),"A",false)); register(lotBody(UUID.randomUUID(),"B",false));
        var one=tree(f.ownerA.browser().get(lots()+"?limit=1")); var two=tree(f.ownerA.browser().get(lots()+"?limit=1&offset=1"));
        assertThat(one.path("items").get(0).path("lotCode").asString()).isEqualTo("A");
        assertThat(two.path("items").get(0).path("lotCode").asString()).isEqualTo("B");
        assertThat(one.path("hasMore").asBoolean()).isTrue();
        assertThat(tree(f.ownerA.browser().get(lots()+"?limit=1&offset=2")).path("hasMore").asBoolean()).isFalse();
        problem(f.ownerA.browser().get(lots()+"?productId="+f.productB),404,"RESOURCE_NOT_FOUND");
        problem(f.ownerA.browser().get(lots()+"?limit=101"),400,"INVALID_REQUEST");
    }

    @Test void aCommittedRevocationRejectsTheWaitingSourceRegistration() throws Exception { ordered(false,false); }
    @Test void anAuthorizedSourceRegistrationCommitsBeforeRevocation() throws Exception { ordered(true,false); }
    @Test void productArchivalRejectsTheWaitingSourceRegistration() throws Exception { ordered(false,true); }
    @Test void aSourceRegistrationCommitsBeforeWaitingProductArchival() throws Exception { ordered(true,true); }
    private void ordered(boolean sourceFirst,boolean archiveProduct) throws Exception {
        var id=register(lotBody(UUID.randomUUID(),"ORDERED",false));
        var operator=f.operator.browser(); var owner=f.ownerA.browser();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var action=sourceFirst?"SOURCE_EVIDENCE_REGISTERED":archiveProduct?"PRODUCT_ARCHIVED":"MEMBERSHIP_REVOKED";
        doAnswer(call -> { entered.countDown(); if (!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Command barrier timed out"); return call.callRealMethod(); })
                .when(audit).securityChange(any(),eq(f.orgA),anyString(),any(),eq(action),anyMap(),anyString());
        try (var workers=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<HttpResponse<String>> observation=() -> operator.write(sources(id),sourceBody(UUID.randomUUID(),f.a1,"ORDERED"));
            java.util.concurrent.Callable<HttpResponse<String>> close=() -> archiveProduct
                    ? owner.write(products()+"/"+f.productA+"/archive",Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Discontinued"))
                    : owner.send("DELETE","/api/v1/organizations/"+f.orgA+"/members/"+f.membership(f.operator),Map.of("version",0,"reason","Assignment ended"),owner.token());
            var first=workers.submit(sourceFirst?observation:close); assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var second=workers.submit(sourceFirst?close:observation);
            var table=archiveProduct?"product":"organization";
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE :query").param("query","%stockrescue."+table+"%").query(Long.class).single()).isPositive());
            release.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(sourceFirst?201:200);
            assertThat(second.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(sourceFirst?200:archiveProduct?409:404);
        } finally { release.countDown(); }
        assertThat(count("consignment_evidence")).isEqualTo(sourceFirst?1:0);
        assertThat(count("consignment_registration")).isEqualTo(sourceFirst?1:0);
    }

    private Map<String,Object> lotBody(UUID request,String code,boolean confirmed) {
        var body=new HashMap<String,Object>(Map.of("requestId",request,"productId",f.productA,"lotCode",code,
                "identityState",confirmed?"CONFIRMED":"CANDIDATE","manufacturerVerified",confirmed,
                "manufacturerProvenance",provenance("producer-reference"),
                "dateMarking",Map.of("labeledValue","2027-04-30","type","USE_BY","precision","DAY","provenance",provenance("label-photo"))));
        if (confirmed) body.put("manufacturerIdentityKey","registry:manufacturer-a"); return body;
    }
    private Map<String,Object> sourceBody(UUID request,UUID store,String reference) {
        var body=new HashMap<String,Object>(Map.of("requestId",request,"provenance",provenance("source-document")));
        if (store!=null) body.put("storeId",store);
        if (reference!=null) { body.put("supplierName","Supplier"); body.put("supplierReference",reference); } return body;
    }
    private Map<String,String> provenance(String reference) { return Map.of("source","Observed document","reference",reference); }
    private UUID register(Map<String,Object> body) throws Exception { var response=f.ownerA.browser().write(lots(),body); assertThat(response.statusCode()).as(response.body()).isEqualTo(201); return UUID.fromString(tree(response).path("id").asString()); }
    private UUID source(UUID lot,Map<String,Object> body) throws Exception { var response=f.ownerA.browser().write(sources(lot),body); assertThat(response.statusCode()).as(response.body()).isEqualTo(201); return UUID.fromString(tree(response).path("id").asString()); }
    private String products() { return "/api/v1/organizations/"+f.orgA+"/products"; }
    private String lots() { return "/api/v1/organizations/"+f.orgA+"/lots"; }
    private String lot(UUID id) { return lots()+"/"+id; }
    private String sources(UUID lot) { return lot(lot)+"/source-evidence"; }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM stockrescue."+table+" WHERE org_id=:org").param("org",f.orgA).query(Long.class).single(); }
    private long audits(String action) { return jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action=:action").param("org",f.orgA).param("action",action).query(Long.class).single(); }
    private void problem(HttpResponse<String> response,int status,String code) { assertThat(response.statusCode()).as(response.body()).isEqualTo(status); assertThat(tree(response).path("code").asString()).isEqualTo(code); }
    private List<HttpResponse<String>> concurrent(Callable<HttpResponse<String>> one,Callable<HttpResponse<String>> two) throws Exception {
        var start=new CountDownLatch(1); try (var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(() -> { start.await(); return one.call(); }); var b=workers.submit(() -> { start.await(); return two.call(); });
            start.countDown(); return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }
    private class Fixture {
        final User ownerA=user(),ownerB=user(),operator=user(),manager=user(),analyst=user(),member=user();
        final UUID orgA=org(ownerA),orgB=org(ownerB),a1=store(orgA,"NORTH"),a2=store(orgA,"SOUTH"),b1=store(orgB,"FOREIGN");
        final UUID productA,productB;
        Fixture() throws Exception {
            member(ownerA,orgA,"ORG_ADMIN",null,null); member(ownerB,orgB,"ORG_ADMIN",null,null);
            member(operator,orgA,"MEMBER",a1,"INVENTORY_OPERATOR"); member(manager,orgA,"MEMBER",a2,"STORE_MANAGER");
            member(analyst,orgA,"MEMBER",a1,"READ_ONLY_ANALYST"); member(member,orgA,"MEMBER",null,null);
            productA=product(ownerA,orgA); productB=product(ownerB,orgB);
        }
        User user() { var u=new User(UUID.randomUUID()); jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)").param("id",u.id).param("email",u.email).update(); jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)").param("id",u.id).param("hash",hash).update(); return u; }
        UUID org(User owner) { var id=UUID.randomUUID(); jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Traceability cooperative',:owner)").param("id",id).param("owner",owner.id).update(); return id; }
        UUID store(UUID org,String code) { var id=UUID.randomUUID(); jdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:id,:org,:code,:code,'UTC')").param("id",id).param("org",org).param("code",code).update(); return id; }
        void member(User actor,UUID org,String role,UUID store,String grant) { var id=UUID.randomUUID(); jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:actor,:role)").param("id",id).param("org",org).param("actor",actor.id).param("role",role).update(); if (store!=null) jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:member,:store,:role)").param("id",UUID.randomUUID()).param("org",org).param("member",id).param("store",store).param("role",grant).update(); }
        UUID membership(User actor) { return jdbc.sql("SELECT id FROM stockrescue.membership WHERE org_id=:org AND user_id=:actor").param("org",orgA).param("actor",actor.id).query(UUID.class).single(); }
        UUID product(User actor,UUID org) throws Exception { var response=actor.browser().write("/api/v1/organizations/"+org+"/products",Map.of("requestId",UUID.randomUUID(),"sku","MILK","name","Milk","baseUnit","EA","category","DAIRY","temperatureClass","CHILLED")); assertThat(response.statusCode()).as(response.body()).isEqualTo(201); return UUID.fromString(tree(response).path("id").asString()); }
    }
    private class User {
        final UUID id; final String email; Browser browser;
        User(UUID id) { this.id=id;this.email=id+"@example.test"; }
        Browser browser() throws Exception { if (browser==null) browser=new Browser(this); return browser; }
    }
    private class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        Browser(User actor) throws Exception { assertThat(write("/api/v1/auth/sign-in",Map.of("email",actor.email,"password",PASSWORD)).statusCode()).isEqualTo(200); }
        String token() throws Exception { return tree(get("/api/v1/auth/csrf")).path("token").asString(); }
        HttpResponse<String> get(String path) throws Exception { return send("GET",path,null,null); }
        HttpResponse<String> write(String path,Object body) throws Exception { return send("POST",path,body,token()); }
        HttpResponse<String> send(String method,String path,Object body,String token) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json");
            if (token!=null) request.header("X-CSRF-TOKEN",token);
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
