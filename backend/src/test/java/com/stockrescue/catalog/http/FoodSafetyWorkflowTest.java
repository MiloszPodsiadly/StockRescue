package com.stockrescue.catalog.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
class FoodSafetyWorkflowTest extends InfrastructureTestBase {
    private static final String PASSWORD="food-safety-test-passphrase";
    private static final Instant NOW=Instant.parse("2026-11-05T22:30:00Z");
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ObjectMapper json;
    @MockitoBean private Clock clock;
    @MockitoSpyBean private AuditWriter audit;
    private String hash;
    private Fixture f;
    @BeforeAll void credentials() { hash=encoder.encode(PASSWORD); }
    @BeforeEach void fixture() throws Exception { when(clock.instant()).thenReturn(NOW);when(clock.getZone()).thenReturn(ZoneOffset.UTC);f=new Fixture(); }

    @Test
    void evaluationUsesCurrentStoreDateAndIsReadOnlyAndUncacheable() throws Exception {
        var id=register("2026-11-05","USE_BY","DAY"); var owner=f.owner.browser();
        var original=tree(owner.get(lot(id))); var auditCount=count("audit_entry");
        var response=owner.get(eligibility(id,f.store,f.zone));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        var result=tree(response);
        assertThat(result.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        assertThat(result.path("storeLocalDate").asString()).isEqualTo("2026-11-05");
        assertThat(result.path("storeTimeZone").asString()).isEqualTo("Europe/Warsaw");
        assertThat(result.path("policy").isNull()).isTrue();
        assertThat(result.path("reasonCodes").toString()).contains("POLICY_MISSING").doesNotContain("USE_BY_EXPIRED");
        when(clock.instant()).thenReturn(Instant.parse("2026-11-05T23:00:00Z"));
        var tomorrow=tree(owner.get(eligibility(id,f.store,f.zone)));
        assertThat(tomorrow.path("decision").asString()).isEqualTo("DENIED");
        assertThat(tomorrow.path("reasonCodes").get(0).asString()).isEqualTo("USE_BY_EXPIRED");
        assertThat(tomorrow.path("storeLocalDate").asString()).isEqualTo("2026-11-06");
        assertThat(tomorrow.path("evaluatedAt").asString()).isEqualTo("2026-11-05T23:00:00Z");
        assertThat(tree(owner.get(lot(id)))).isEqualTo(original);
        assertThat(count("audit_entry")).isEqualTo(auditCount); assertThat(count("lot_correction")).isZero();
    }

    @Test
    void policyReviewsRemainUnverifiedAndNewVersionsReplaceCurrentEvaluationEvidence() throws Exception {
        var id=register("2027-04-30","USE_BY","DAY"); var owner=f.owner.browser();
        assertThat(tree(owner.get(policies())).path("current").isNull()).isTrue();
        var body=review(0,"NO_NORMALIZATION"); var first=owner.write(policies(),body);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(tree(first).path("approvalStatus").asString()).isEqualTo("UNVERIFIED");
        var initialEvaluation=tree(owner.get(eligibility(id,f.store,f.zone)));
        assertThat(initialEvaluation.path("policy").path("id")).isEqualTo(tree(first).path("id"));
        assertThat(initialEvaluation.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        var second=owner.write(policies(),review(1,"END_OF_LABELED_PERIOD"));
        assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
        assertThat(tree(second).path("profileId")).isEqualTo(tree(first).path("profileId"));
        var fresh=tree(owner.get(eligibility(id,f.store,f.zone)));
        assertThat(fresh.path("policy").path("version").asLong()).isEqualTo(2);
        assertThat(fresh.path("policy").path("id")).isEqualTo(tree(second).path("id"));
        assertThat(initialEvaluation.path("policy").path("version").asLong()).isEqualTo(1);
        assertThat(tree(owner.write(policies(),body))).isEqualTo(tree(first));
        var altered=new HashMap<>(body); altered.put("reason","Changed replay");
        problem(owner.write(policies(),altered),409,"CATALOG_CONFLICT");
        var page=tree(owner.get(policies()+"?limit=1"));
        assertThat(page.path("current")).isEqualTo(tree(second));assertThat(page.path("hasMore").asBoolean()).isTrue();
        assertThat(tree(owner.get(policies()+"?offset=1&limit=1")).path("items").get(0)).isEqualTo(tree(first));
        assertThat(tree(owner.get(policies().replace("AMBIENT","CHILLED"))).path("current").isNull()).isTrue();
        assertThat(count("food_safety_policy_version")).isEqualTo(2);
        assertThat(audits("FOOD_SAFETY_POLICY_REVIEWED")).isEqualTo(2);
    }

    @Test
    void partialLabelsAndRequestedNormalizationNeverProduceCutoffsOrApproval() throws Exception {
        var owner=f.owner.browser(); assertThat(owner.write(policies(),review(0,"END_OF_LABELED_PERIOD")).statusCode()).isEqualTo(201);
        for (var example:new String[][]{{" 2027-02 ","BEST_BEFORE_END","MONTH"},{"2027","BEST_BEFORE_END","YEAR"},{null,"UNKNOWN","UNKNOWN"},{"2027-02-31","USE_BY","DAY"}}) {
            var id=register(example[0],example[1],example[2]); var result=tree(owner.get(eligibility(id,f.store,f.zone)));
            assertThat(result.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
            assertThat(result.path("reasonCodes").toString()).contains("POLICY_APPROVAL_UNVERIFIED");
            var detail=tree(owner.get(lot(id)));
            assertThat(detail.path("dateMarking").path("normalizedCutoff").isNull()).isTrue();
            assertThat(detail.path("version").asLong()).isZero();
            if (example[0]!=null) assertThat(detail.path("dateMarking").path("labeledValue").asString()).isEqualTo(example[0]);
        }
    }

    @Test
    void administratorsCannotOverrideExpiryRecallDamageTemperatureOrApprovalVerification() throws Exception {
        var owner=f.owner.browser();
        for (var field:List.of("allowExpiredUseBy","allowExpiredBestBefore","ignoreRecall","ignoreDamage","ignoreTemperatureMismatch","allowMissingEvidence","approvalStatus")) {
            var permissive=review(0,"NO_NORMALIZATION"); permissive.put(field,field.equals("approvalStatus")?"VERIFIED":true);
            problem(owner.write(policies(),permissive),400,"INVALID_REQUEST");
        }
        var missingSource=review(0,"NO_NORMALIZATION");missingSource.put("reviewProvenance",Map.of("source","Admin statement"));
        problem(owner.write(policies(),missingSource),422,"INVALID_REQUEST");
        assertThat(owner.write(policies(),review(0,"NO_NORMALIZATION")).statusCode()).isEqualTo(201);
        var expired=register("2026-11-04","BEST_BEFORE","DAY");
        assertThat(tree(owner.get(eligibility(expired,f.store,f.zone))).path("decision").asString()).isEqualTo("DENIED");
        var id=register("2027-04-30","USE_BY","DAY");
        var result=tree(owner.get(eligibility(id,f.store,f.incompatibleZone)));
        assertThat(result.path("decision").asString()).isEqualTo("DENIED");
        assertThat(result.path("reasonCodes").toString()).contains("TEMPERATURE_CLASS_MISMATCH");
        assertThat(count("food_safety_policy_version")).isEqualTo(1);
    }

    @Test
    void blockEvidenceUsesTheSameImmutableHistoryAndCannotBeClearedByMetadata() throws Exception {
        var id=register("2027-04-30","USE_BY","DAY"); var owner=f.owner.browser(); var before=tree(owner.get(lot(id)));
        var body=status(0,"RECALLED","DAMAGED");var result=owner.write(statusPath(id),body);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        var record=tree(result).path("correction");
        assertThat(record.path("kind").asString()).isEqualTo("SAFETY_STATUS");
        assertThat(record.path("before")).isEqualTo(before);
        assertThat(record.path("after")).isEqualTo(tree(owner.get(lot(id))));
        assertThat(record.path("after").path("dateMarking")).isEqualTo(before.path("dateMarking"));
        var evaluation=tree(owner.get(eligibility(id,f.store,f.zone)));
        assertThat(evaluation.path("decision").asString()).isEqualTo("DENIED");
        assertThat(evaluation.path("reasonCodes").get(0).asString()).isEqualTo("LOT_RECALLED");
        assertThat(evaluation.path("reasonCodes").get(1).asString()).isEqualTo("LOT_DAMAGED");
        assertThat(evaluation.path("lotVersion").asLong()).isEqualTo(1);
        assertThat(tree(owner.write(statusPath(id),body))).isEqualTo(tree(result));
        problem(owner.write(statusPath(id),status(1,"NOT_REPORTED","NOT_REPORTED")),409,"CATALOG_CONFLICT");
        assertThat(tree(owner.get(lot(id)+"/corrections")).path("items").get(0)).isEqualTo(record);
        var changes=json.readTree(jdbc.sql("SELECT changes::text FROM stockrescue.audit_entry WHERE org_id=:org AND action='LOT_SAFETY_EVIDENCE_RECORDED'")
                .param("org",f.org).query(String.class).single());
        assertThat(changes.path("before").path("recallState").asString()).isEqualTo("UNKNOWN");
        assertThat(changes.path("after").path("recallState").asString()).isEqualTo("RECALLED");
        assertThat(audits("LOT_SAFETY_EVIDENCE_RECORDED")).isEqualTo(1);
    }

    @Test
    void policyAndSafetyEvidenceWritesRequireExplicitAuthorityAndEvaluationRequiresTheCurrentStoreGrant() throws Exception {
        var id=register("2027-04-30","USE_BY","DAY");var operator=f.operator.browser(); var owner=f.owner.browser();
        for (var role:List.of("INVENTORY_OPERATOR","STORE_MANAGER","READ_ONLY_ANALYST")) {
            jdbc.sql("UPDATE stockrescue.store_grant SET role=:role,version=version+1 WHERE org_id=:org").param("org",f.org).param("role",role).update();
            assertThat(operator.get(eligibility(id,f.store,f.zone)).statusCode()).isEqualTo(200);
            problem(operator.write(policies(),review(0,"NO_NORMALIZATION")),403,"ACCESS_DENIED");
            problem(operator.write(statusPath(id),status(0,"RECALLED","UNKNOWN")),403,"ACCESS_DENIED");
        }
        problem(operator.get(eligibility(id,f.otherStore,f.otherZone)),404,"RESOURCE_NOT_FOUND");
        problem(owner.get(eligibility(id,f.store,f.otherZone)),404,"RESOURCE_NOT_FOUND");
        problem(f.foreign.browser().get(eligibility(id,f.store,f.zone)),404,"RESOURCE_NOT_FOUND");
        problem(owner.get(eligibility(UUID.randomUUID(),f.store,f.zone)),404,"RESOURCE_NOT_FOUND");
        problem(f.foreign.browser().write("/api/v1/organizations/"+f.otherOrg+"/lots/"+id+"/safety-status-evidence",status(0,"RECALLED","UNKNOWN")),404,"RESOURCE_NOT_FOUND");
        problem(new Browser().get(eligibility(id,f.store,f.zone)),401,"AUTH_REQUIRED");
        problem(owner.send("POST",policies(),review(0,"NO_NORMALIZATION"),null),403,"CSRF_INVALID");
        jdbc.sql("UPDATE stockrescue.store_grant SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE org_id=:org").param("org",f.org).update();
        problem(operator.get(eligibility(id,f.store,f.zone)),404,"RESOURCE_NOT_FOUND");
        assertThat(count("food_safety_policy_version")).isZero();assertThat(count("lot_correction")).isZero();
    }

    @Test
    void inactiveContextDeniesWhileMissingStorageRemainsExplicitlyUnverified() throws Exception {
        var id=register("2027-04-30","USE_BY","DAY");var owner=f.owner.browser();
        var unknown=tree(owner.get(eligibility(id,f.store,null)));
        assertThat(unknown.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        assertThat(unknown.path("evidence").path("storage").asString()).isEqualTo("MISSING");
        jdbc.sql("UPDATE stockrescue.storage_zone SET active=FALSE,version=version+1,updated_at=clock_timestamp() WHERE id=:id").param("id",f.zone).update();
        var archived=tree(owner.get(eligibility(id,f.store,f.zone)));
        assertThat(archived.path("decision").asString()).isEqualTo("DENIED");
        assertThat(archived.path("reasonCodes").toString()).contains("ZONE_ARCHIVED");
        jdbc.sql("UPDATE stockrescue.store SET active=FALSE,version=version+1,updated_at=clock_timestamp() WHERE id=:id").param("id",f.store).update();
        assertThat(tree(owner.get(eligibility(id,f.store,f.zone))).path("reasonCodes").toString()).contains("STORE_ARCHIVED");
        problem(f.operator.browser().get(eligibility(id,f.store,f.zone)),404,"RESOURCE_NOT_FOUND");
    }

    @Test
    void concurrentPolicyAppendsHaveOneWinnerAndIdenticalRetriesRecoverTheOriginalVersion() throws Exception {
        var one=f.owner.browser();var two=new Browser(f.owner);
        var results=concurrent(() -> one.write(policies(),review(0,"NO_NORMALIZATION")),() -> two.write(policies(),review(0,"END_OF_LABELED_PERIOD")));
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,409);
        var next=review(1,"NO_NORMALIZATION");
        var duplicate=concurrent(() -> one.write(policies(),next),() -> two.write(policies(),next));
        assertThat(duplicate.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,200);
        assertThat(tree(duplicate.get(0))).isEqualTo(tree(duplicate.get(1)));
        assertThat(count("food_safety_policy_version")).isEqualTo(2);assertThat(audits("FOOD_SAFETY_POLICY_REVIEWED")).isEqualTo(2);
    }

    @Test
    void aStatusWriteAndDateCorrectionOnOneVersionCannotOverwriteEachOther() throws Exception {
        var id=register("2027-04-30","USE_BY","DAY");var one=f.owner.browser();var two=new Browser(f.owner);
        var metadata=Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Reviewed original label","evidenceReviewed",true,
                "identityState","CONFIRMED","manufacturerVerified",true,"manufacturerIdentityKey","registry:maker-a",
                "manufacturerProvenance",provenance("registry-17"),"dateMarking",marking("2027-05-01","USE_BY","DAY"));
        var results=concurrent(() -> one.write(statusPath(id),status(0,"RECALLED","UNKNOWN")),() -> two.write(lot(id)+"/corrections",metadata));
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,409);
        assertThat(tree(one.get(lot(id))).path("version").asLong()).isEqualTo(1);
        assertThat(count("lot_correction")).isEqualTo(1);
        assertThat(audits("LOT_METADATA_CORRECTED")+audits("LOT_SAFETY_EVIDENCE_RECORDED")).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackBothPolicyAndSafetyEvidenceWithTheirRetryResults() throws Exception {
        var owner=f.owner.browser();var policy=review(0,"NO_NORMALIZATION");
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).securityChange(any(),eq(f.org),anyString(),any(),eq("FOOD_SAFETY_POLICY_REVIEWED"),anyMap(),anyString());
        problem(owner.write(policies(),policy),500,"INTERNAL_ERROR");assertThat(count("food_safety_policy_version")).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.org),anyString(),any(),eq("FOOD_SAFETY_POLICY_REVIEWED"),anyMap(),anyString());
        assertThat(owner.write(policies(),policy).statusCode()).isEqualTo(201);
        var id=register("2027-04-30","USE_BY","DAY");var before=tree(owner.get(lot(id)));var status=status(0,"RECALLED","UNKNOWN");
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).securityChange(any(),eq(f.org),anyString(),eq(id),eq("LOT_SAFETY_EVIDENCE_RECORDED"),anyMap(),anyString());
        problem(owner.write(statusPath(id),status),500,"INTERNAL_ERROR");
        assertThat(tree(owner.get(lot(id)))).isEqualTo(before);assertThat(count("lot_correction")).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.org),anyString(),eq(id),eq("LOT_SAFETY_EVIDENCE_RECORDED"),anyMap(),anyString());
        assertThat(owner.write(statusPath(id),status).statusCode()).isEqualTo(201);
    }

    @Test
    void databaseGuardsPreventUnverifiedApprovalHistoryMutationAndUnrecordedStatusUpdates() throws Exception {
        var owner=f.owner.browser();assertThat(owner.write(policies(),review(0,"NO_NORMALIZATION")).statusCode()).isEqualTo(201);
        for (var sql:List.of("UPDATE stockrescue.food_safety_policy_version SET approval_status='VERIFIED' WHERE org_id=:org",
                "DELETE FROM stockrescue.food_safety_policy_version WHERE org_id=:org"))
            assertThatThrownBy(() -> jdbc.sql(sql).param("org",f.org).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO stockrescue.food_safety_policy_version(id,org_id,profile_id,temperature_class,version,partial_date_rule,approval_status,
                    review_source,review_reference,reason,actor_user_id,recorded_by_membership_id,request_id,payload_digest)
                SELECT :id,org_id,profile_id,temperature_class,version+1,partial_date_rule,'VERIFIED',review_source,review_reference,reason,
                    actor_user_id,recorded_by_membership_id,:request,payload_digest FROM stockrescue.food_safety_policy_version WHERE org_id=:org
                """).param("id",UUID.randomUUID()).param("request",UUID.randomUUID()).param("org",f.org).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var id=register("2027-04-30","USE_BY","DAY");
        assertThatThrownBy(() -> jdbc.sql("UPDATE stockrescue.manufacturer_lot SET recall_state='RECALLED',status_source='Claim',status_reference='ref',version=version+1 WHERE org_id=:org AND id=:id")
                .param("org",f.org).param("id",id).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(tree(owner.get(lot(id))).path("recallState").asString()).isEqualTo("UNKNOWN");
        assertThat(count("food_safety_policy_version")).isEqualTo(1);assertThat(count("lot_correction")).isZero();
    }

    @Test void aCommittedRevocationRejectsTheWaitingPolicyReview() throws Exception { orderedPolicy(false); }
    @Test void anAuthorizedPolicyReviewCommitsBeforeRevocation() throws Exception { orderedPolicy(true); }
    private void orderedPolicy(boolean writeFirst) throws Exception {
        var owner=f.owner.browser();var admin=f.coowner.browser();var command=review(0,"NO_NORMALIZATION");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var action=writeFirst?"FOOD_SAFETY_POLICY_REVIEWED":"MEMBERSHIP_REVOKED";
        doAnswer(call -> { entered.countDown();if (!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Command barrier timed out");return call.callRealMethod(); })
                .when(audit).securityChange(any(),eq(f.org),anyString(),any(),eq(action),anyMap(),anyString());
        try (var workers=Executors.newFixedThreadPool(2)) {
            Callable<HttpResponse<String>> review=() -> owner.write(policies(),command);
            Callable<HttpResponse<String>> revoke=() -> admin.send("DELETE","/api/v1/organizations/"+f.org+"/members/"+f.membership(f.owner),Map.of("version",0,"reason","Assignment ended"),admin.token());
            var first=workers.submit(writeFirst?review:revoke);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var second=workers.submit(writeFirst?revoke:review);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%stockrescue.organization%'").query(Long.class).single()).isPositive());
            release.countDown();assertThat(first.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(writeFirst?201:200);
            assertThat(second.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(writeFirst?200:404);
        } finally { release.countDown(); }
        problem(owner.write(policies(),command),404,"RESOURCE_NOT_FOUND");
        assertThat(count("food_safety_policy_version")).isEqualTo(writeFirst?1:0);
    }

    private Map<String,Object> review(long version,String rule) { return new HashMap<>(Map.of("requestId",UUID.randomUUID(),"expectedVersion",version,"reason","Review of class-specific source evidence","partialDateRule",rule,"reviewProvenance",provenance("policy-document"))); }
    private Map<String,Object> status(long version,String recall,String damage) { return new HashMap<>(Map.of("requestId",UUID.randomUUID(),"expectedVersion",version,"reason","Reviewed supplier safety notice","recallState",recall,"damageState",damage,"provenance",provenance("supplier-notice"))); }
    private Map<String,Object> marking(String label,String type,String precision) { var body=new HashMap<String,Object>(Map.of("type",type,"precision",precision,"provenance",provenance("label-photo")));if (label!=null) body.put("labeledValue",label);return body; }
    private Map<String,String> provenance(String reference) { return Map.of("source","Observed document","reference",reference); }
    private UUID register(String label,String type,String precision) throws Exception {
        var body=Map.of("requestId",UUID.randomUUID(),"productId",f.product,"lotCode",UUID.randomUUID().toString(),"identityState","CONFIRMED","manufacturerIdentityKey","registry:maker-a",
                "manufacturerVerified",true,"manufacturerProvenance",provenance("registry-17"),"dateMarking",marking(label,type,precision));
        var result=f.owner.browser().write("/api/v1/organizations/"+f.org+"/lots",body);assertThat(result.statusCode()).as(result.body()).isEqualTo(201);return UUID.fromString(tree(result).path("id").asString());
    }
    private String lot(UUID id) { return "/api/v1/organizations/"+f.org+"/lots/"+id; }
    private String policies() { return "/api/v1/organizations/"+f.org+"/food-safety-profiles/AMBIENT/versions"; }
    private String statusPath(UUID id) { return lot(id)+"/safety-status-evidence"; }
    private String eligibility(UUID id,UUID store,UUID zone) { return lot(id)+"/eligibility?operation=SALE&storeId="+store+(zone==null?"":"&zoneId="+zone); }
    private JsonNode tree(HttpResponse<String> result) { return json.readTree(result.body()); }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM stockrescue."+table+" WHERE org_id=:org").param("org",f.org).query(Long.class).single(); }
    private long audits(String action) { return jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action=:action").param("org",f.org).param("action",action).query(Long.class).single(); }
    private void problem(HttpResponse<String> result,int status,String code) { assertThat(result.statusCode()).as(result.body()).isEqualTo(status);assertThat(tree(result).path("code").asString()).isEqualTo(code); }
    private List<HttpResponse<String>> concurrent(Callable<HttpResponse<String>> one,Callable<HttpResponse<String>> two) throws Exception { var start=new CountDownLatch(1);try (var workers=Executors.newFixedThreadPool(2)) { var a=workers.submit(() -> { start.await();return one.call(); });var b=workers.submit(() -> { start.await();return two.call(); });start.countDown();return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)); } }
    private class Fixture {
        final User owner=user(),coowner=user(),operator=user(),foreign=user();
        final UUID org=org(owner),otherOrg=org(foreign),store=store("NORTH"),otherStore=store("SOUTH"),zone=zone(store,"AMBIENT"),incompatibleZone=zone(store,"CHILLED"),otherZone=zone(otherStore,"AMBIENT"),product;
        Fixture() throws Exception {
            member(owner,org,"ORG_ADMIN");member(coowner,org,"ORG_ADMIN");member(operator,org,"MEMBER");member(foreign,otherOrg,"ORG_ADMIN");
            jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:member,:store,'INVENTORY_OPERATOR')")
                    .param("id",UUID.randomUUID()).param("org",org).param("member",membership(operator)).param("store",store).update();
            var result=owner.browser().write("/api/v1/organizations/"+org+"/products",Map.of("requestId",UUID.randomUUID(),"sku","BREAD","name","Packaged bread","baseUnit","EA","temperatureClass","AMBIENT"));
            assertThat(result.statusCode()).as(result.body()).isEqualTo(201);product=UUID.fromString(tree(result).path("id").asString());
        }
        User user() { var u=new User(UUID.randomUUID());jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)").param("id",u.id).param("email",u.email).update();jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)").param("id",u.id).param("hash",hash).update();return u; }
        UUID org(User owner) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Safety cooperative',:owner)").param("id",id).param("owner",owner.id).update();return id; }
        UUID store(String code) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:id,:org,:code,:code,'Europe/Warsaw')").param("id",id).param("org",org).param("code",code).update();return id; }
        UUID zone(UUID store,String temperature) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES (:id,:org,:store,:name,:temperature)").param("id",id).param("org",org).param("store",store).param("name",temperature).param("temperature",temperature).update();return id; }
        void member(User actor,UUID org,String role) { jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:actor,:role)").param("id",UUID.randomUUID()).param("org",org).param("actor",actor.id).param("role",role).update(); }
        UUID membership(User actor) { return jdbc.sql("SELECT id FROM stockrescue.membership WHERE org_id=:org AND user_id=:actor").param("org",org).param("actor",actor.id).query(UUID.class).single(); }
    }
    private class User { final UUID id;final String email;Browser browser;User(UUID id) { this.id=id;this.email=id+"@example.test"; }Browser browser() throws Exception { if (browser==null) browser=new Browser(this);return browser; } }
    private class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        Browser() { }
        Browser(User actor) throws Exception { var result=write("/api/v1/auth/sign-in",Map.of("email",actor.email,"password",PASSWORD));assertThat(result.statusCode()).as(result.body()).isEqualTo(200); }
        String token() throws Exception { return tree(get("/api/v1/auth/csrf")).path("token").asString(); }
        HttpResponse<String> get(String path) throws Exception { return send("GET",path,null,null); }
        HttpResponse<String> write(String path,Object body) throws Exception { return send("POST",path,body,token()); }
        HttpResponse<String> send(String method,String path,Object body,String token) throws Exception { var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json");if (token!=null) request.header("X-CSRF-TOKEN",token);return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
