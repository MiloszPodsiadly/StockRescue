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
class LotCorrectionTest extends InfrastructureTestBase {
    private static final String PASSWORD="lot-correction-test-passphrase";
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
    void reviewedCorrectionPreservesExactBeforeAfterEvidenceAndIndependentSourceIdentity() throws Exception {
        var id=register("REVIEWED"); var browser=f.owner.browser();
        var original=tree(browser.get(lot(id)));
        var source=browser.write(lot(id)+"/source-evidence",Map.of("requestId",UUID.randomUUID(),"provenance",provenance("supplier-original")));
        assertThat(source.statusCode()).as(source.body()).isEqualTo(201);
        var command=correction(0," 2027-05 ");
        var result=browser.write(corrections(id),command);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        var evidence=tree(result).path("correction"); var current=tree(result).path("lot");
        assertThat(evidence.path("before")).isEqualTo(original);
        assertThat(evidence.path("after")).isEqualTo(current);
        assertThat(evidence.path("actorUserId").asString()).isEqualTo(f.owner.id.toString());
        assertThat(evidence.path("actorMembershipId").asString()).isEqualTo(f.membership(f.owner).toString());
        assertThat(evidence.path("recordedAt").asString()).isNotBlank();
        assertThat(evidence.path("reason").asString()).isEqualTo(command.get("reason"));
        assertThat(current.path("version").asLong()).isEqualTo(1);
        assertThat(current.path("dateMarking").path("labeledValue").asString()).isEqualTo(" 2027-05 ");
        assertThat(current.path("dateMarking").path("normalizedCutoff").isNull()).isTrue();
        assertThat(tree(browser.get(lot(id)))).isEqualTo(current);
        assertThat(tree(browser.get(lot(id)+"/source-evidence")).path("items").get(0)).isEqualTo(tree(source));
        assertThat(jdbc.sql("SELECT c.after_evidence=to_jsonb(l) FROM stockrescue.lot_correction c JOIN stockrescue.manufacturer_lot l ON l.org_id=c.org_id AND l.id=c.lot_id WHERE c.org_id=:org")
                .param("org",f.org).query(Boolean.class).single()).isTrue();
        var changes=json.readTree(jdbc.sql("SELECT changes::text FROM stockrescue.audit_entry WHERE org_id=:org AND action='LOT_METADATA_CORRECTED'")
                .param("org",f.org).query(String.class).single());
        assertThat(changes.path("correctionId")).isEqualTo(evidence.path("id"));
        assertThat(changes.path("before").path("data").path("dateMarking").path("labeledValue").asString()).isEqualTo(" 2027-04 ");
        assertThat(changes.path("after").path("data").path("dateMarking").path("labeledValue").asString()).isEqualTo(" 2027-05 ");
        assertThat(tree(browser.get(corrections(id))).path("items").get(0)).isEqualTo(evidence);
        assertThat(tree(browser.get(corrections(id))).path("currentVersion").asLong()).isEqualTo(1);
        assertThat(current.toString()).doesNotContain("ALLOWED");
    }

    @Test
    void replayRecoversOriginalCorrectionAlongsideCurrentVersionEvenAfterParentArchival() throws Exception {
        var id=register("RECOVERY"); var browser=f.owner.browser(); var command=correction(0,"2027-05");
        var first=browser.write(corrections(id),command); assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        var second=browser.write(corrections(id),correction(1,"2027-06")); assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
        var replay=browser.write(corrections(id),command);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(tree(replay).path("correction")).isEqualTo(tree(first).path("correction"));
        assertThat(tree(replay).path("lot")).isEqualTo(tree(second).path("lot"));
        var history=tree(browser.get(corrections(id)+"?limit=1"));
        assertThat(history.path("items").get(0).path("resultVersion").asLong()).isEqualTo(2);
        assertThat(history.path("hasMore").asBoolean()).isTrue();
        assertThat(tree(browser.get(corrections(id)+"?offset=1&limit=1")).path("items").get(0)).isEqualTo(tree(first).path("correction"));
        var changed=new HashMap<>(command); changed.put("reason","Changed retry");
        problem(browser.write(corrections(id),changed),409,"CATALOG_CONFLICT");
        jdbc.sql("UPDATE stockrescue.product SET active=FALSE,version=version+1 WHERE org_id=:org AND id=:id").param("org",f.org).param("id",f.product).update();
        problem(browser.write(corrections(id),correction(2,"2027-07")),409,"CATALOG_INACTIVE");
        assertThat(browser.write(corrections(id),command).statusCode()).isEqualTo(200);
        jdbc.sql("UPDATE stockrescue.manufacturer_lot SET active=FALSE,version=version+1 WHERE org_id=:org AND id=:id").param("org",f.org).param("id",id).update();
        assertThat(tree(browser.write(corrections(id),command)).path("lot").path("active").asBoolean()).isFalse();
        problem(browser.write(corrections(id),correction(3,"2027-07")),409,"CATALOG_INACTIVE");
        assertThat(count("lot_correction")).isEqualTo(2); assertThat(audits()).isEqualTo(2);
    }

    @Test
    void twoTabsEditingOneVersionHaveOneWinnerAndNoPartialLoser() throws Exception {
        var id=register("TWO-TABS"); var one=f.owner.browser(); var two=new Browser(f.owner);
        var results=concurrent(() -> one.write(corrections(id),correction(0,"2027-05")),() -> two.write(corrections(id),correction(0,"2027-06")));
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,409);
        var winner=results.stream().filter(result -> result.statusCode()==201).findFirst().orElseThrow();
        assertThat(tree(one.get(lot(id)))).isEqualTo(tree(winner).path("lot"));
        assertThat(count("lot_correction")).isEqualTo(1); assertThat(audits()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateCorrectionRecoversOneRecordedOutcome() throws Exception {
        var id=register("DUPLICATE"); var one=f.owner.browser(); var two=new Browser(f.owner); var command=correction(0,"2027-05");
        var results=concurrent(() -> one.write(corrections(id),command),() -> two.write(corrections(id),command));
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,200);
        assertThat(tree(results.get(0))).isEqualTo(tree(results.get(1)));
        assertThat(count("lot_correction")).isEqualTo(1); assertThat(audits()).isEqualTo(1);
    }

    @Test
    void reusingOneRequestAcrossDifferentLotsRollsBackTheLosingMutation() throws Exception {
        var a=register("LOT-A"); var b=register("LOT-B"); var one=f.owner.browser(); var two=new Browser(f.owner); var command=correction(0,"2027-05");
        var results=concurrent(() -> one.write(corrections(a),command),() -> two.write(corrections(b),command));
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,409);
        assertThat(tree(one.get(lot(a))).path("version").asLong()+tree(one.get(lot(b))).path("version").asLong()).isEqualTo(1);
        assertThat(count("lot_correction")).isEqualTo(1); assertThat(audits()).isEqualTo(1);
    }

    @Test
    void knownManufacturerCannotBeRelabeledAndReviewedConfirmationNeverMergesCandidates() throws Exception {
        var id=register("IDENTITY"); var browser=f.owner.browser();
        var relabel=correction(0,"2027-05"); relabel.put("manufacturerProvenance",Map.of("source","Different source","reference","evidence"));
        problem(browser.write(corrections(id),relabel),409,"CATALOG_CONFLICT");
        var confirmation=correction(0,"2027-05"); confirmation.put("identityState","CONFIRMED"); confirmation.put("manufacturerVerified",true);
        confirmation.put("manufacturerIdentityKey","registry:maker-a"); confirmation.put("manufacturerProvenance",Map.of("source","Reviewed manufacturer registry","reference","registry-17"));
        var confirmed=browser.write(corrections(id),confirmation);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(201);
        var current=tree(confirmed).path("lot");
        assertThat(current.path("id").asString()).isEqualTo(id.toString());
        assertThat(current.path("confirmedByMembershipId").asString()).isEqualTo(f.membership(f.owner).toString());
        var wrong=correction(1,"2027-06"); wrong.put("identityState","CONFIRMED"); wrong.put("manufacturerVerified",true);
        wrong.put("manufacturerIdentityKey","registry:maker-b"); wrong.put("manufacturerProvenance",confirmation.get("manufacturerProvenance"));
        problem(browser.write(corrections(id),wrong),409,"CATALOG_CONFLICT");
        var another=register("IDENTITY"); var collision=new HashMap<>(confirmation); collision.put("requestId",UUID.randomUUID());
        problem(browser.write(corrections(another),collision),409,"CATALOG_CONFLICT");
        assertThat(tree(browser.get(lot(another))).path("identityState").asString()).isEqualTo("CANDIDATE");
        assertThat(tree(browser.get(lot(another))).path("version").asLong()).isZero();
        assertThat(count("manufacturer_lot")).isEqualTo(2); assertThat(count("lot_correction")).isEqualTo(1);
        var known=registration("KNOWN-CANDIDATE"); known.put("manufacturerIdentityKey","registry:known");
        var knownId=register(known);
        var differentManufacturer=new HashMap<>(confirmation); differentManufacturer.put("requestId",UUID.randomUUID());
        problem(browser.write(corrections(knownId),differentManufacturer),409,"CATALOG_CONFLICT");
        assertThat(tree(browser.get(lot(knownId))).path("manufacturerIdentityKey").asString()).isEqualTo("registry:known");
    }

    @Test
    void correctionsRequireCompleteReviewedCommandsAndARealMetadataChange() throws Exception {
        var id=register("VALIDATION"); var browser=f.owner.browser();
        for (var field:List.of("requestId","expectedVersion","reason","evidenceReviewed","manufacturerProvenance","manufacturerVerified","dateMarking")) {
            var missing=correction(0,"2027-05"); missing.remove(field);
            problem(browser.write(corrections(id),missing),400,"INVALID_REQUEST");
        }
        var blank=correction(0,"2027-05"); blank.put("reason","  "); problem(browser.write(corrections(id),blank),400,"INVALID_REQUEST");
        var noChange=correction(0," 2027-04 "); noChange.put("manufacturerProvenance",provenance("producer-original"));
        problem(browser.write(corrections(id),noChange),422,"INVALID_REQUEST");
        problem(browser.get(corrections(id)+"?limit=101"),400,"INVALID_REQUEST");
        assertThat(count("lot_correction")).isZero(); assertThat(audits()).isZero();
    }

    @Test
    void morePreciseDatesRequireActualLabelEvidenceAndNeverImplySafetyApproval() throws Exception {
        var body=registration("UNKNOWN"); body.put("dateMarking",Map.of("type","UNKNOWN","precision","UNKNOWN"));
        var id=register(body); var browser=f.owner.browser();
        for (var label:List.of("2027-04","2027-02-31","01/02/2027")) {
            var command=correction(0,label); command.put("dateMarking",marking(label,"USE_BY","DAY",true));
            problem(browser.write(corrections(id),command),422,"INVALID_REQUEST");
        }
        var missing=correction(0,"2027-04-30"); missing.put("dateMarking",marking("2027-04-30","USE_BY","DAY",false));
        problem(browser.write(corrections(id),missing),422,"INVALID_REQUEST");
        var unreviewed=correction(0,"2027-04-30"); unreviewed.put("evidenceReviewed",false);
        problem(browser.write(corrections(id),unreviewed),422,"INVALID_REQUEST");
        var reviewed=correction(0,"2027-04-30"); reviewed.put("dateMarking",marking("2027-04-30","USE_BY","DAY",true));
        var result=browser.write(corrections(id),reviewed); assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        assertThat(tree(result).path("correction").path("before").path("dateMarking").path("precision").asString()).isEqualTo("UNKNOWN");
        assertThat(tree(result).path("lot").path("dateMarking").path("precision").asString()).isEqualTo("DAY");
        assertThat(tree(result).path("lot").path("dateMarking").path("normalizedCutoff").isNull()).isTrue();
        assertThat(result.body()).doesNotContain("ALLOWED");
        assertThat(count("lot_correction")).isEqualTo(1);
    }

    @Test
    void correctionAuthorityIsOrganizationScopedAndRetryRechecksCurrentMembership() throws Exception {
        var id=register("AUTHORITY"); var command=correction(0,"2027-05");
        for (var role:List.of("INVENTORY_OPERATOR","STORE_MANAGER","READ_ONLY_ANALYST")) {
            jdbc.sql("UPDATE stockrescue.store_grant SET role=:role,version=version+1 WHERE org_id=:org").param("org",f.org).param("role",role).update();
            problem(f.operator.browser().write(corrections(id),command),403,"ACCESS_DENIED");
        }
        problem(new Browser().write(corrections(id),command),401,"AUTH_REQUIRED");
        problem(f.owner.browser().send("POST",corrections(id),command,null),403,"CSRF_INVALID");
        problem(f.foreign.browser().get(corrections(id)),404,"RESOURCE_NOT_FOUND");
        problem(f.foreign.browser().write("/api/v1/organizations/"+f.otherOrg+"/lots/"+id+"/corrections",command),404,"RESOURCE_NOT_FOUND");
        var fake=new HashMap<>(command); fake.put("productId",UUID.randomUUID());
        problem(f.owner.browser().write(corrections(id),fake),400,"INVALID_REQUEST");
        assertThat(f.owner.browser().write(corrections(id),command).statusCode()).isEqualTo(201);
        assertThat(tree(f.operator.browser().get(corrections(id))).path("items")).hasSize(1);
        jdbc.sql("UPDATE stockrescue.membership SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE org_id=:org AND user_id=:actor")
                .param("org",f.org).param("actor",f.owner.id).update();
        problem(f.owner.browser().write(corrections(id),command),404,"RESOURCE_NOT_FOUND");
        problem(f.owner.browser().get(corrections(id)),404,"RESOURCE_NOT_FOUND");
        assertThat(count("lot_correction")).isEqualTo(1);
    }

    @Test
    void auditAndDatabaseFailureRollBackMetadataHistoryAndRetryOutcomeTogether() throws Exception {
        var id=register("ROLLBACK"); var browser=f.owner.browser(); var original=tree(browser.get(lot(id))); var command=correction(0,"2027-05");
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).securityChange(any(),eq(f.org),anyString(),eq(id),eq("LOT_METADATA_CORRECTED"),anyMap(),anyString());
        problem(browser.write(corrections(id),command),500,"INTERNAL_ERROR");
        assertThat(tree(browser.get(lot(id)))).isEqualTo(original);
        assertThat(count("lot_correction")).isZero(); assertThat(audits()).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.org),anyString(),eq(id),eq("LOT_METADATA_CORRECTED"),anyMap(),anyString());
        jdbc.sql("ALTER TABLE stockrescue.lot_correction ADD CONSTRAINT correction_failure_probe CHECK (org_id<>'"+f.org+"'::uuid)").update();
        try {
            problem(browser.write(corrections(id),command),409,"CATALOG_CONFLICT");
            assertThat(tree(browser.get(lot(id)))).isEqualTo(original);
            assertThat(count("lot_correction")).isZero(); assertThat(audits()).isZero();
        } finally { jdbc.sql("ALTER TABLE stockrescue.lot_correction DROP CONSTRAINT correction_failure_probe").update(); }
        assertThat(browser.write(corrections(id),command).statusCode()).isEqualTo(201);
        assertThat(count("lot_correction")).isEqualTo(1); assertThat(audits()).isEqualTo(1);
    }

    @Test
    void sqlCannotCommitUnrecordedMetadataChangesOrRewriteCorrectionHistory() throws Exception {
        var id=register("SQL-GUARD"); var browser=f.owner.browser(); var original=tree(browser.get(lot(id)));
        assertThatThrownBy(() -> jdbc.sql("UPDATE stockrescue.manufacturer_lot SET labeled_value='2027-05',version=version+1 WHERE org_id=:org AND id=:id")
                .param("org",f.org).param("id",id).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(tree(browser.get(lot(id)))).isEqualTo(original);
        assertThatThrownBy(() -> jdbc.sql("UPDATE stockrescue.manufacturer_lot SET manufacturer_source='Different source',version=version+1 WHERE org_id=:org AND id=:id")
                .param("org",f.org).param("id",id).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(browser.write(corrections(id),correction(0,"2027-05")).statusCode()).isEqualTo(201);
        for (var sql:List.of("UPDATE stockrescue.lot_correction SET reason='Replacement' WHERE org_id=:org","DELETE FROM stockrescue.lot_correction WHERE org_id=:org"))
            assertThatThrownBy(() -> jdbc.sql(sql).param("org",f.org).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("lot_correction")).isEqualTo(1);
    }

    @Test void aCommittedRevocationRejectsTheWaitingCorrection() throws Exception { ordered(false,false); }
    @Test void anAuthorizedCorrectionCommitsBeforeRevocation() throws Exception { ordered(true,false); }
    @Test void productArchivalRejectsTheWaitingCorrection() throws Exception { ordered(false,true); }
    @Test void aCorrectionCommitsBeforeWaitingProductArchival() throws Exception { ordered(true,true); }
    private void ordered(boolean correctionFirst,boolean archiveProduct) throws Exception {
        var id=register("ORDERED"); var author=f.owner.browser(); var admin=f.coowner.browser(); var command=correction(0,"2027-05");
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var action=correctionFirst?"LOT_METADATA_CORRECTED":archiveProduct?"PRODUCT_ARCHIVED":"MEMBERSHIP_REVOKED";
        doAnswer(call -> { entered.countDown(); if (!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Command barrier timed out"); return call.callRealMethod(); })
                .when(audit).securityChange(any(),eq(f.org),anyString(),any(),eq(action),anyMap(),anyString());
        try (var workers=Executors.newFixedThreadPool(2)) {
            Callable<HttpResponse<String>> correction=() -> author.write(corrections(id),command);
            Callable<HttpResponse<String>> close=() -> archiveProduct
                    ? admin.write("/api/v1/organizations/"+f.org+"/products/"+f.product+"/archive",Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Discontinued"))
                    : admin.send("DELETE","/api/v1/organizations/"+f.org+"/members/"+f.membership(f.owner),Map.of("version",0,"reason","Assignment ended"),admin.token());
            var first=workers.submit(correctionFirst?correction:close); assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var second=workers.submit(correctionFirst?close:correction);
            var table=archiveProduct?"product":"organization";
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE :query")
                    .param("query","%stockrescue."+table+"%").query(Long.class).single()).isPositive());
            release.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(correctionFirst?201:200);
            assertThat(second.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(correctionFirst?200:archiveProduct?409:404);
        } finally { release.countDown(); }
        assertThat(count("lot_correction")).isEqualTo(correctionFirst?1:0); assertThat(audits()).isEqualTo(correctionFirst?1:0);
    }

    private Map<String,Object> registration(String code) {
        return new HashMap<>(Map.of("requestId",UUID.randomUUID(),"productId",f.product,"lotCode",code,"identityState","CANDIDATE","manufacturerVerified",false,
                "manufacturerProvenance",provenance("producer-original"),"dateMarking",marking(" 2027-04 ","BEST_BEFORE_END","MONTH",true)));
    }
    private Map<String,Object> correction(long version,String label) {
        return new HashMap<>(Map.of("requestId",UUID.randomUUID(),"expectedVersion",version,"reason","Reviewed original manufacturer label",
                "evidenceReviewed",true,"identityState","CANDIDATE","manufacturerVerified",false,"manufacturerProvenance",provenance("producer-reviewed"),
                "dateMarking",marking(label,"BEST_BEFORE_END","MONTH",true)));
    }
    private Map<String,Object> marking(String label,String type,String precision,boolean source) {
        var result=new HashMap<String,Object>(Map.of("labeledValue",label,"type",type,"precision",precision));
        if (source) result.put("provenance",provenance("label-photo")); return result;
    }
    private Map<String,String> provenance(String reference) { return Map.of("source","Observed document","reference",reference); }
    private UUID register(String code) throws Exception { return register(registration(code)); }
    private UUID register(Map<String,Object> body) throws Exception { var result=f.owner.browser().write(base(),body); assertThat(result.statusCode()).as(result.body()).isEqualTo(201); return UUID.fromString(tree(result).path("id").asString()); }
    private String base() { return "/api/v1/organizations/"+f.org+"/lots"; }
    private String lot(UUID id) { return base()+"/"+id; }
    private String corrections(UUID id) { return lot(id)+"/corrections"; }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM stockrescue."+table+" WHERE org_id=:org").param("org",f.org).query(Long.class).single(); }
    private long audits() { return jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action='LOT_METADATA_CORRECTED'").param("org",f.org).query(Long.class).single(); }
    private void problem(HttpResponse<String> response,int status,String code) { assertThat(response.statusCode()).as(response.body()).isEqualTo(status); assertThat(tree(response).path("code").asString()).isEqualTo(code); }
    private List<HttpResponse<String>> concurrent(Callable<HttpResponse<String>> one,Callable<HttpResponse<String>> two) throws Exception {
        var start=new CountDownLatch(1); try (var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(() -> { start.await(); return one.call(); }); var b=workers.submit(() -> { start.await(); return two.call(); });
            start.countDown(); return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }
    private class Fixture {
        final User owner=user(),coowner=user(),operator=user(),foreign=user();
        final UUID org=org(owner),otherOrg=org(foreign),store=UUID.randomUUID(),product;
        Fixture() throws Exception {
            jdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:id,:org,'NORTH','North','UTC')").param("id",store).param("org",org).update();
            member(owner,org,"ORG_ADMIN"); member(coowner,org,"ORG_ADMIN"); member(operator,org,"MEMBER"); member(foreign,otherOrg,"ORG_ADMIN");
            jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:member,:store,'INVENTORY_OPERATOR')")
                    .param("id",UUID.randomUUID()).param("org",org).param("member",membership(operator)).param("store",store).update();
            var result=owner.browser().write("/api/v1/organizations/"+org+"/products",Map.of("requestId",UUID.randomUUID(),"sku","MILK","name","Milk","baseUnit","EA","temperatureClass","CHILLED"));
            assertThat(result.statusCode()).as(result.body()).isEqualTo(201); product=UUID.fromString(tree(result).path("id").asString());
        }
        User user() { var u=new User(UUID.randomUUID()); jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)").param("id",u.id).param("email",u.email).update(); jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)").param("id",u.id).param("hash",hash).update(); return u; }
        UUID org(User owner) { var id=UUID.randomUUID(); jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Correction cooperative',:owner)").param("id",id).param("owner",owner.id).update(); return id; }
        void member(User actor,UUID org,String role) { jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:actor,:role)").param("id",UUID.randomUUID()).param("org",org).param("actor",actor.id).param("role",role).update(); }
        UUID membership(User actor) { return jdbc.sql("SELECT id FROM stockrescue.membership WHERE org_id=:org AND user_id=:actor").param("org",org).param("actor",actor.id).query(UUID.class).single(); }
    }
    private class User {
        final UUID id; final String email; Browser browser;
        User(UUID id) { this.id=id;this.email=id+"@example.test"; }
        Browser browser() throws Exception { if (browser==null) browser=new Browser(this); return browser; }
    }
    private class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        Browser() { }
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
