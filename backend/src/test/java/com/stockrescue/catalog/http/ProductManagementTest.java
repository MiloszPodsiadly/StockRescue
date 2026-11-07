package com.stockrescue.catalog.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.stockrescue.catalog.persistence.ProductRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductManagementTest extends InfrastructureTestBase {
    private static final String PASSWORD="product-catalog-test-passphrase";
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ProductRepository products;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private AuditWriter audit;
    private String hash;
    private Fixture f;
    @BeforeAll void credentials() { hash=encoder.encode(PASSWORD); }
    @BeforeEach void fixture() { f=new Fixture(); }

    @Test
    void organizationCatalogRightsDoNotTurnStoreGrantsIntoCatalogManagement() throws Exception {
        var owner=f.ownerA.browser();
        var product=create(owner,"DAIRY");
        var anonymous=new Browser();
        problem(anonymous.get(base(f.orgA)),401,"AUTH_REQUIRED");
        problem(owner.send("POST",base(f.orgA),body(UUID.randomUUID(),"MISSING-CSRF"),null),403,"CSRF_INVALID");
        for (var member:List.of(f.member,f.manager,f.operator,f.analyst)) {
            var browser=member.browser();
            var read=browser.get(path(f.orgA,product));
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(tree(read).path("allowedActions").toString()).isEqualTo("[\"READ_CATALOG\"]");
            assertThat(tree(browser.get(base(f.orgA))).path("canCreate").asBoolean()).isFalse();
            problem(browser.write("POST",base(f.orgA),body(UUID.randomUUID(),"FORBIDDEN")),403,"ACCESS_DENIED");
            problem(browser.write("POST",path(f.orgA,product)+"/archive",archive(UUID.randomUUID(),0)),403,"ACCESS_DENIED");
            problem(browser.write("POST",path(f.orgA,product)+"/category-corrections",categoryCorrection(UUID.randomUUID(),0,"BAKERY")),403,"ACCESS_DENIED");
        }
        problem(f.ownerB.browser().get(path(f.orgA,product)),404,"RESOURCE_NOT_FOUND");
        problem(owner.get(path(f.orgB,product)),404,"RESOURCE_NOT_FOUND");
        problem(f.ownerB.browser().get(path(f.orgB,product)),404,"RESOURCE_NOT_FOUND");
        problem(f.ownerB.browser().write("POST",path(f.orgB,product)+"/archive",archive(UUID.randomUUID(),0)),404,"RESOURCE_NOT_FOUND");
        problem(f.ownerB.browser().write("POST",base(f.orgA),body(UUID.randomUUID(),"FOREIGN")),404,"RESOURCE_NOT_FOUND");
        jdbc.sql("UPDATE stockrescue.store_grant SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE org_id=:org").param("org",f.orgA).update();
        assertThat(f.manager.browser().get(path(f.orgA,product)).statusCode()).isEqualTo(200);
        problem(f.manager.browser().write("POST",base(f.orgA),body(UUID.randomUUID(),"STILL-FORBIDDEN")),403,"ACCESS_DENIED");
    }

    @Test
    void invalidCatalogValuesAndUnsupportedUnitsLeaveNoDurableCommand() throws Exception {
        var owner=f.ownerA.browser();
        for (var change:List.of(Map.of("baseUnit","KG"),Map.of("gtin","036000291453"),Map.of("sku","café"),Map.of("name","bad\u0085name"))) {
            var body=body(UUID.randomUUID(),"INVALID");
            body.putAll(change);
            problem(owner.write("POST",base(f.orgA),body),422,"INVALID_REQUEST");
        }
        problem(owner.get(base(f.orgA)+"?limit=0"),400,"INVALID_REQUEST");
        problem(owner.get(base(f.orgA)+"?limit=101"),400,"INVALID_REQUEST");
        problem(owner.get(base(f.orgA)+"?state=UNKNOWN"),400,"INVALID_REQUEST");
        assertThat(count("product")).isZero();
        assertThat(count("product_command")).isZero();
    }

    @Test
    void canonicalCreationReplayRecoversOneIdentityAndRejectsChangedPayloads() throws Exception {
        var owner=f.ownerA.browser();
        var request=UUID.randomUUID();
        var body=body(request," milk-1 ");
        body.put("name","  Fresh milk  ");
        var created=owner.write("POST",base(f.orgA),body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var expected=tree(created);
        assertThat(expected.path("sku").asString()).isEqualTo("MILK-1");
        assertThat(expected.path("name").asString()).isEqualTo("Fresh milk");
        assertThat(expected.path("gtin").asString()).isEqualTo("036000291452");
        body.put("sku","MILK-1"); body.put("name","Fresh milk");
        var replay=owner.write("POST",base(f.orgA),body);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(tree(replay)).isEqualTo(expected);
        body.put("name","Altered intent");
        problem(owner.write("POST",base(f.orgA),body),409,"CATALOG_CONFLICT");
        problem(owner.write("POST",base(f.orgA),body(UUID.randomUUID(),"MILK-1")),409,"CATALOG_CONFLICT");
        assertThat(count("product")).isEqualTo(1);
        assertThat(count("product_command")).isEqualTo(1);
        assertThat(audits("PRODUCT_CREATED")).isEqualTo(1);
        assertThat(f.ownerB.browser().write("POST",base(f.orgB),body(request,"MILK-1")).statusCode()).isEqualTo(201);
        f.member(f.ownerB,f.orgA,"ORG_ADMIN",null);
        assertThat(f.ownerB.browser().write("POST",base(f.orgA),body(request,"OTHER-ACTOR")).statusCode()).isEqualTo(201);
    }

    @Test
    void concurrentDuplicateRequestsCommitOneProductOutcomeAndAudit() throws Exception {
        var first=f.ownerA.browser();
        var second=new Browser(f.ownerA);
        var body=body(UUID.randomUUID(),"SAME-REQUEST");
        var responses=concurrent(() -> first.write("POST",base(f.orgA),body),() -> second.write("POST",base(f.orgA),body));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,200);
        assertThat(tree(responses.get(0)).path("id")).isEqualTo(tree(responses.get(1)).path("id"));
        assertThat(count("product")).isEqualTo(1);
        assertThat(count("product_command")).isEqualTo(1);
        assertThat(audits("PRODUCT_CREATED")).isEqualTo(1);
        var collisions=concurrent(() -> first.write("POST",base(f.orgA),body(UUID.randomUUID(),"SKU-RACE")),
                () -> second.write("POST",base(f.orgA),body(UUID.randomUUID(),"sku-race")));
        assertThat(collisions.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,409);
        assertThat(count("product")).isEqualTo(2);
        assertThat(count("product_command")).isEqualTo(2);
    }

    @Test
    void archiveReplaysItsCommittedOutcomeWhilePreservingSkuAndVersionHistory() throws Exception {
        var owner=f.ownerA.browser();
        var request=UUID.randomUUID();
        var create=body(request,"ARCHIVE");
        var product=UUID.fromString(tree(owner.write("POST",base(f.orgA),create)).path("id").asString());
        var archive=archive(request,0);
        var result=owner.write("POST",path(f.orgA,product)+"/archive",archive);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(tree(result).path("active").asBoolean()).isFalse();
        assertThat(tree(result).path("version").asLong()).isEqualTo(1);
        assertThat(tree(result).path("archivedAt").isNull()).isFalse();
        assertThat(tree(owner.write("POST",path(f.orgA,product)+"/archive",archive))).isEqualTo(tree(result));
        archive.put("reason","Changed intent");
        problem(owner.write("POST",path(f.orgA,product)+"/archive",archive),409,"CATALOG_CONFLICT");
        problem(owner.write("POST",path(f.orgA,product)+"/archive",archive(UUID.randomUUID(),0)),409,"CATALOG_INACTIVE");
        problem(owner.write("POST",base(f.orgA),body(UUID.randomUUID(),"ARCHIVE")),409,"CATALOG_CONFLICT");
        assertThat(tree(owner.write("POST",base(f.orgA),create))).isEqualTo(tree(result));
        assertThat(tree(owner.get(path(f.orgA,product)))).isEqualTo(tree(result));
        assertThat(audits("PRODUCT_ARCHIVED")).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND resource_id=:id
                    AND actor_user_id=:actor AND action='PRODUCT_ARCHIVED' AND reason='Discontinued product reviewed'
                    AND changes#>>'{before,active}'='true' AND changes#>>'{after,active}'='false'
                    AND changes#>>'{before,version}'='0' AND changes#>>'{after,version}'='1'
                """).param("org",f.orgA).param("id",product).param("actor",f.ownerA.id).query(Long.class).single()).isEqualTo(1);
        assertThat(count("product_command")).isEqualTo(2);
        var other=create(owner,"STALE");
        problem(owner.write("POST",path(f.orgA,other)+"/archive",archive(UUID.randomUUID(),1)),409,"CATALOG_CONFLICT");
        assertThat(tree(owner.get(path(f.orgA,other))).path("version").asLong()).isZero();
    }

    @Test
    void categoryCorrectionUsesExpectedVersionDurableReplayAndImmutableAuditEvidence() throws Exception {
        var owner=f.ownerA.browser();
        var product=create(owner,"CATEGORY-CORRECTION");
        var request=UUID.randomUUID();
        var correction=categoryCorrection(request,0,"BAKERY");
        var saved=owner.write("POST",path(f.orgA,product)+"/category-corrections",correction);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        var expected=tree(saved);
        assertThat(expected.path("category").asString()).isEqualTo("BAKERY");
        assertThat(expected.path("version").asLong()).isEqualTo(1);
        assertThat(tree(owner.write("POST",path(f.orgA,product)+"/category-corrections",correction))).isEqualTo(expected);
        var changed=categoryCorrection(request,0,"UNCLASSIFIED");
        problem(owner.write("POST",path(f.orgA,product)+"/category-corrections",changed),409,"CATALOG_CONFLICT");
        problem(owner.write("POST",path(f.orgA,product)+"/category-corrections",categoryCorrection(UUID.randomUUID(),0,"UNCLASSIFIED")),409,"CATALOG_CONFLICT");
        problem(owner.write("POST",path(f.orgB,product)+"/category-corrections",categoryCorrection(UUID.randomUUID(),0,"BAKERY")),404,"RESOURCE_NOT_FOUND");
        assertThat(audits("PRODUCT_CATEGORY_CORRECTED")).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND resource_id=:id
                    AND action='PRODUCT_CATEGORY_CORRECTED' AND reason='Corrected from the package label'
                    AND changes#>>'{before,data,category}'='DAIRY'
                    AND changes#>>'{after,data,category}'='BAKERY'
                    AND changes#>>'{before,version}'='0' AND changes#>>'{after,version}'='1'
                """).param("org",f.orgA).param("id",product).query(Long.class).single()).isEqualTo(1);
        f.member(f.ownerB,f.orgA,"ORG_ADMIN",null);
        jdbc.sql("UPDATE stockrescue.membership SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE org_id=:org AND user_id=:actor")
                .param("org",f.orgA).param("actor",f.ownerA.id).update();
        problem(owner.write("POST",path(f.orgA,product)+"/category-corrections",categoryCorrection(UUID.randomUUID(),1,"UNCLASSIFIED")),404,"RESOURCE_NOT_FOUND");
    }

    @Test
    void categoryCorrectionAuditFailureRollsBackTheProductVersionAndRetryReceipt() throws Exception {
        var owner=f.ownerA.browser();
        var product=create(owner,"CATEGORY-ROLLBACK");
        var correction=categoryCorrection(UUID.randomUUID(),0,"BAKERY");
        doThrow(new IllegalStateException("audit unavailable")).when(audit).securityChange(any(),eq(f.orgA),eq("product"),eq(product),eq("PRODUCT_CATEGORY_CORRECTED"),anyMap(),anyString());
        problem(owner.write("POST",path(f.orgA,product)+"/category-corrections",correction),500,"INTERNAL_ERROR");
        assertThat(tree(owner.get(path(f.orgA,product))).path("category").asString()).isEqualTo("DAIRY");
        assertThat(tree(owner.get(path(f.orgA,product))).path("version").asLong()).isZero();
        assertThat(count("product_command")).isEqualTo(1);
        doCallRealMethod().when(audit).securityChange(any(),eq(f.orgA),eq("product"),eq(product),eq("PRODUCT_CATEGORY_CORRECTED"),anyMap(),anyString());
        var retried=owner.write("POST",path(f.orgA,product)+"/category-corrections",correction);
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(200);
        assertThat(tree(retried).path("category").asString()).isEqualTo("BAKERY");
        assertThat(count("product_command")).isEqualTo(2);
        assertThat(audits("PRODUCT_CATEGORY_CORRECTED")).isEqualTo(1);
    }

    @Test
    void searchAndPaginationUseLiteralFiltersAndStableOrganizationScopedOrdering() throws Exception {
        var owner=f.ownerA.browser();
        create(owner,"C-THIRD"); create(owner,"A-FIRST");
        var middle=body(UUID.randomUUID(),"B-SECOND"); middle.put("name","Offer 100%_fresh");
        var id=UUID.fromString(tree(owner.write("POST",base(f.orgA),middle)).path("id").asString());
        create(f.ownerB.browser(),f.orgB,"FOREIGN");
        var one=tree(owner.get(base(f.orgA)+"?limit=1"));
        assertThat(one.path("items").get(0).path("sku").asString()).isEqualTo("A-FIRST");
        assertThat(one.path("hasMore").asBoolean()).isTrue();
        assertThat(tree(owner.get(base(f.orgA)+"?offset=1&limit=1")).path("items").get(0).path("id").asString()).isEqualTo(id.toString());
        assertThat(tree(owner.get(base(f.orgA)+"?offset=2&limit=1")).path("hasMore").asBoolean()).isFalse();
        for (var q:List.of("b-second","100%25","_fresh")) {
            var filtered=tree(owner.get(base(f.orgA)+"?q="+q)).path("items");
            assertThat(filtered).hasSize(1);
            assertThat(filtered.get(0).path("id").asString()).isEqualTo(id.toString());
        }
        assertThat(tree(owner.get(base(f.orgA)+"?q=036000291452")).path("items")).hasSize(3);
        assertThat(tree(owner.get(base(f.orgA)+"?q=FOREIGN")).path("items")).isEmpty();
        owner.write("POST",path(f.orgA,id)+"/archive",archive(UUID.randomUUID(),0));
        assertThat(tree(owner.get(base(f.orgA))).path("items")).hasSize(2);
        assertThat(tree(owner.get(base(f.orgA)+"?state=ALL")).path("items")).hasSize(3);
        assertThat(tree(owner.get(base(f.orgA)+"?state=ARCHIVED")).path("items")).hasSize(1);
    }

    @Test
    void revokedMembershipInactiveOrganizationAndDisabledAccountCannotReadOrWrite() throws Exception {
        var owner=f.ownerA.browser();
        var member=f.member.browser();
        var product=create(owner,"ACCESS");
        jdbc.sql("UPDATE stockrescue.membership SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE org_id=:org AND user_id=:actor")
                .param("org",f.orgA).param("actor",f.member.id).update();
        problem(member.get(path(f.orgA,product)),404,"RESOURCE_NOT_FOUND");
        var manager=f.manager.browser();
        jdbc.sql("UPDATE stockrescue.user_account SET status='DISABLED' WHERE id=:id").param("id",f.manager.id).update();
        assertThat(manager.get(path(f.orgA,product)).statusCode()).isEqualTo(401);
        jdbc.sql("UPDATE stockrescue.organization SET status='ARCHIVED',version=version+1 WHERE id=:org").param("org",f.orgA).update();
        problem(owner.get(base(f.orgA)),404,"RESOURCE_NOT_FOUND");
        problem(owner.write("POST",base(f.orgA),body(UUID.randomUUID(),"AFTER-ARCHIVE")),404,"RESOURCE_NOT_FOUND");
        assertThat(count("product_command")).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackProductAndRetryReservationAndArchiveVersion() throws Exception {
        var owner=f.ownerA.browser();
        var body=body(UUID.randomUUID(),"ROLLBACK");
        doThrow(new IllegalStateException("audit unavailable")).when(audit).securityChange(any(),eq(f.orgA),eq("product"),any(),anyString(),anyMap(),anyString());
        problem(owner.write("POST",base(f.orgA),body),500,"INTERNAL_ERROR");
        assertThat(count("product")).isZero(); assertThat(count("product_command")).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.orgA),eq("product"),any(),anyString(),anyMap(),anyString());
        var created=owner.write("POST",base(f.orgA),body);
        assertThat(created.statusCode()).isEqualTo(201);
        var id=UUID.fromString(tree(created).path("id").asString());
        var archive=archive(UUID.randomUUID(),0);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).securityChange(any(),eq(f.orgA),eq("product"),any(),eq("PRODUCT_ARCHIVED"),anyMap(),anyString());
        problem(owner.write("POST",path(f.orgA,id)+"/archive",archive),500,"INTERNAL_ERROR");
        assertThat(tree(owner.get(path(f.orgA,id))).path("version").asLong()).isZero();
        assertThat(count("product_command")).isEqualTo(1);
        doCallRealMethod().when(audit).securityChange(any(),eq(f.orgA),eq("product"),any(),eq("PRODUCT_ARCHIVED"),anyMap(),anyString());
        assertThat(owner.write("POST",path(f.orgA,id)+"/archive",archive).statusCode()).isEqualTo(200);
        assertThat(audits("PRODUCT_ARCHIVED")).isEqualTo(1);
    }

    @Test
    void aDatabaseRejectionAndAnOrphanReservationCannotLeavePartialOutcomes() throws Exception {
        var owner=f.ownerA.browser();
        var body=body(UUID.randomUUID(),"SQL-REJECTION");
        jdbc.sql("ALTER TABLE stockrescue.product ADD CONSTRAINT catalog_failure_probe CHECK (org_id<>'"+f.orgA+"'::uuid OR sku<>'SQL-REJECTION')").update();
        try {
            problem(owner.write("POST",base(f.orgA),body),409,"CATALOG_CONFLICT");
            assertThat(count("product")).isZero(); assertThat(count("product_command")).isZero();
        } finally { jdbc.sql("ALTER TABLE stockrescue.product DROP CONSTRAINT catalog_failure_probe").update(); }
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                products.reserve(f.ownerA.id,f.orgA,ProductRepository.Operation.CREATE,UUID.randomUUID(),UUID.randomUUID(),0,new byte[32])))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("product_command")).isZero();
        assertThat(owner.write("POST",base(f.orgA),body).statusCode()).isEqualTo(201);
    }

    @Test void committedMembershipRevocationRejectsAWaitingCatalogWrite() throws Exception { orderedRevocation(false); }
    @Test void anAuthorizedCatalogWriteCommitsBeforeAWaitingRevocation() throws Exception { orderedRevocation(true); }

    private void orderedRevocation(boolean writeFirst) throws Exception {
        f.member(f.ownerB,f.orgA,"ORG_ADMIN",null);
        var author=f.ownerA.browser(); var administrator=f.ownerB.browser();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var action=writeFirst?"PRODUCT_CREATED":"MEMBERSHIP_REVOKED";
        doAnswer(call -> { entered.countDown(); if (!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Authorization barrier timed out"); return call.callRealMethod(); })
                .when(audit).securityChange(any(),eq(f.orgA),anyString(),any(),eq(action),anyMap(),anyString());
        var request=body(UUID.randomUUID(),"ORDERED");
        try (var workers=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<HttpResponse<String>> write=() -> author.write("POST",base(f.orgA),request);
            java.util.concurrent.Callable<HttpResponse<String>> revoke=() -> administrator.write("DELETE","/api/v1/organizations/"+f.orgA+"/members/"+f.membership(f.ownerA),Map.of("version",0,"reason","Colleague assignment ended"));
            var first=workers.submit(writeFirst?write:revoke);
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var second=workers.submit(writeFirst?revoke:write);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%stockrescue.organization%'").query(Long.class).single()).isPositive());
            release.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(writeFirst?201:200);
            assertThat(second.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(writeFirst?200:404);
        } finally { release.countDown(); }
        problem(author.write("POST",base(f.orgA),request),404,"RESOURCE_NOT_FOUND");
        assertThat(count("product")).isEqualTo(writeFirst?1:0);
        assertThat(count("product_command")).isEqualTo(writeFirst?1:0);
        assertThat(audits("PRODUCT_CREATED")).isEqualTo(writeFirst?1:0);
    }

    private long count(String table) { return jdbc.sql("SELECT count(*) FROM stockrescue."+table+" WHERE org_id=:org").param("org",f.orgA).query(Long.class).single(); }
    private long audits(String action) { return jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action=:action").param("org",f.orgA).param("action",action).query(Long.class).single(); }
    private Map<String,Object> body(UUID request,String sku) { return new HashMap<>(Map.of("requestId",request,"sku",sku,"name","Fresh milk","gtin","036000291452","baseUnit","EA","category","DAIRY","temperatureClass","CHILLED")); }
    private Map<String,Object> archive(UUID request,long version) { return new HashMap<>(Map.of("requestId",request,"expectedVersion",version,"reason","Discontinued product reviewed")); }
    private Map<String,Object> categoryCorrection(UUID request,long version,String category) { return new HashMap<>(Map.of("requestId",request,"expectedVersion",version,"category",category,"reason","Corrected from the package label")); }
    private UUID create(Browser browser,String sku) throws Exception { return create(browser,f.orgA,sku); }
    private UUID create(Browser browser,UUID org,String sku) throws Exception { var result=browser.write("POST",base(org),body(UUID.randomUUID(),sku)); assertThat(result.statusCode()).as(result.body()).isEqualTo(201); return UUID.fromString(tree(result).path("id").asString()); }
    private String base(UUID org) { return "/api/v1/organizations/"+org+"/products"; }
    private String path(UUID org,UUID product) { return base(org)+"/"+product; }
    private JsonNode tree(HttpResponse<String> result) { return json.readTree(result.body()); }
    private void problem(HttpResponse<String> result,int status,String code) { assertThat(result.statusCode()).as(result.body()).isEqualTo(status); assertThat(tree(result).path("code").asString()).isEqualTo(code); assertThat(tree(result).path("correlationId").asString()).isEqualTo(result.headers().firstValue("X-Correlation-ID").orElseThrow()); }
    private List<HttpResponse<String>> concurrent(java.util.concurrent.Callable<HttpResponse<String>> one,java.util.concurrent.Callable<HttpResponse<String>> two) throws Exception {
        var start=new CountDownLatch(1);
        try (var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(() -> { start.await(); return one.call(); }); var b=workers.submit(() -> { start.await(); return two.call(); });
            start.countDown(); return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }
    private class Fixture {
        final User ownerA=user(),ownerB=user(),member=user(),manager=user(),operator=user(),analyst=user();
        final UUID orgA=org(ownerA),orgB=org(ownerB),store=UUID.randomUUID();
        Fixture() {
            jdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:id,:org,'NORTH','North','UTC')").param("id",store).param("org",orgA).update();
            member(ownerA,orgA,"ORG_ADMIN",null); member(ownerB,orgB,"ORG_ADMIN",null); member(member,orgA,"MEMBER",null);
            member(manager,orgA,"MEMBER","STORE_MANAGER"); member(operator,orgA,"MEMBER","INVENTORY_OPERATOR"); member(analyst,orgA,"MEMBER","READ_ONLY_ANALYST");
        }
        User user() { var user=new User(UUID.randomUUID()); jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)").param("id",user.id).param("email",user.email).update(); jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)").param("id",user.id).param("hash",hash).update(); return user; }
        UUID org(User owner) { var org=UUID.randomUUID(); jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:org,'Catalog cooperative',:actor)").param("org",org).param("actor",owner.id).update(); return org; }
        void member(User user,UUID org,String role,String grant) {
            var id=UUID.randomUUID(); jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:user,:role)").param("id",id).param("org",org).param("user",user.id).param("role",role).update();
            if (grant!=null) jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:member,:store,:role)").param("id",UUID.randomUUID()).param("org",org).param("member",id).param("store",store).param("role",grant).update();
        }
        UUID membership(User user) { return jdbc.sql("SELECT id FROM stockrescue.membership WHERE org_id=:org AND user_id=:user").param("org",orgA).param("user",user.id).query(UUID.class).single(); }
    }
    private class User {
        final UUID id; final String email; Browser browser;
        User(UUID id) { this.id=id; this.email=id+"@example.test"; }
        Browser browser() throws Exception { if (browser==null) browser=new Browser(this); return browser; }
    }
    private class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        Browser() { }
        Browser(User user) throws Exception { assertThat(write("POST","/api/v1/auth/sign-in",Map.of("email",user.email,"password",PASSWORD)).statusCode()).isEqualTo(200); }
        HttpResponse<String> get(String path) throws Exception { return send("GET",path,null,null); }
        HttpResponse<String> write(String method,String path,Object body) throws Exception { var token=tree(get("/api/v1/auth/csrf")).path("token").asString(); return send(method,path,body,token); }
        HttpResponse<String> send(String method,String path,Object body,String token) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json");
            if (token!=null) request.header("X-CSRF-TOKEN",token);
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
