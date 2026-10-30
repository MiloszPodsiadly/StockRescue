package com.stockrescue.stores.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreManagementTest extends InfrastructureTestBase {
    private static final String PASSWORD = "store-management-test-passphrase";
    private static final String REASON = "Reviewed store configuration change";
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder encoder;
    @MockitoSpyBean private AuditWriter audit;
    private String hash;
    private Fixture f;
    @BeforeAll void passwordFixture() { hash = encoder.encode(PASSWORD); }
    @BeforeEach void storesFixture() { f = new Fixture(); }

    @Test
    void creationRetriesPreserveOneStoreAndCodeUniquenessIsScopedToItsOrganization() throws Exception {
        var owner = f.ownerA.browser();
        var request = UUID.randomUUID();
        var body = create(request, " south ", "  South store  ", "Europe/Warsaw");
        var created = owner.write("POST", base(f.orgA), body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var store = tree(created);
        var id = UUID.fromString(store.path("id").asString());
        assertThat(store.path("code").asString()).isEqualTo("SOUTH");
        assertThat(store.path("name").asString()).isEqualTo("South store");
        assertThat(store.path("countryCode").asString()).isEqualTo("PL");
        assertThat(store.path("addressLine1").isNull()).isTrue();
        assertThat(tree(owner.write("POST", base(f.orgA), body))).isEqualTo(store);
        assertThat(owner.write("POST", base(f.orgA), body).statusCode()).isEqualTo(200);
        problem(owner.write("POST", base(f.orgA), create(request, "SOUTH", "Different intent", "Europe/Warsaw")), 409, "STORE_CONFLICT");
        problem(owner.write("POST", base(f.orgA), create(UUID.randomUUID(), "south", "Another store", "UTC")), 409, "STORE_CONFLICT");
        assertThat(f.ownerB.browser().write("POST", base(f.orgB), create(request, "SOUTH", "South store", "Europe/Warsaw")).statusCode()).isEqualTo(201);
        problem(f.ownerB.browser().get(path(f.orgB, id)), 404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.store_creation_request WHERE org_id=:org AND store_id=:id").param("org", f.orgA).param("id", id).query(Long.class).single()).isEqualTo(1);
        var entry = jdbc.sql("SELECT actor_user_id,changes::text,correlation_id FROM stockrescue.audit_entry WHERE org_id=:org AND resource_id=:id")
                .param("org", f.orgA).param("id", id).query().singleRow();
        assertThat(entry.get("actor_user_id")).isEqualTo(f.ownerA.id);
        assertThat(entry.get("correlation_id")).isEqualTo(created.headers().firstValue("X-Correlation-ID").orElseThrow());
        assertThat(entry.get("changes").toString()).contains("SUCCEEDED", "SOUTH", "Europe/Warsaw").doesNotContain(f.ownerA.email);
    }

    @Test
    void concurrentCreationRetriesAndDuplicateCodesNeverCreateMultipleRows() throws Exception {
        var first = f.ownerA.browser();
        var second = new Browser(f.ownerA);
        var body = create(UUID.randomUUID(), "RETRY", "Retry store", "UTC");
        var retries = concurrent(() -> first.write("POST", base(f.orgA), body), () -> second.write("POST", base(f.orgA), body));
        assertThat(retries).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 200);
        assertThat(tree(retries.getFirst()).path("id")).isEqualTo(tree(retries.getLast()).path("id"));
        var duplicates = concurrent(() -> first.write("POST", base(f.orgA), create(UUID.randomUUID(), "RACE", "One", "UTC")),
                () -> second.write("POST", base(f.orgA), create(UUID.randomUUID(), "RACE", "Two", "UTC")));
        assertThat(duplicates).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.store WHERE org_id=:org AND code_normalized IN ('RETRY','RACE')")
                .param("org", f.orgA).query(Long.class).single()).isEqualTo(2);
        assertThat(audits("STORE_CREATED")).isEqualTo(2);
    }

    @Test
    void storeManagersEditOnlyAssignedMetadataAndReadRolesCannotMutate() throws Exception {
        var manager = f.manager.browser();
        var assigned = tree(manager.get(path(f.orgA, f.a1)));
        assertThat(assigned.path("allowedActions").toString()).contains("EDIT_STORE").doesNotContain("ARCHIVE_STORE");
        var changed = manager.write("PATCH", path(f.orgA, f.a1), update(0, "Renamed North", "Europe/Berlin"));
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(tree(changed).path("code").asString()).isEqualTo("NORTH");
        assertThat(tree(changed).path("version").asLong()).isEqualTo(1);
        for (var user : List.of(f.operator, f.analyst)) {
            assertThat(tree(user.browser().get(path(f.orgA, f.a1))).path("allowedActions").toString()).isEqualTo("[\"READ_STORE\"]");
            problem(user.browser().write("PATCH", path(f.orgA, f.a1), update(1, "Forbidden", "UTC")), 403, "ACCESS_DENIED");
            problem(user.browser().write("POST", path(f.orgA, f.a1) + "/archive", version(1)), 403, "ACCESS_DENIED");
        }
        for (var user : List.of(f.manager, f.operator, f.analyst, f.unassigned)) {
            problem(user.browser().write("POST", base(f.orgA), create(UUID.randomUUID(), "FORBIDDEN", "Forbidden", "UTC")), 403, "ACCESS_DENIED");
            problem(user.browser().get(path(f.orgA, f.a2)), 404, "RESOURCE_NOT_FOUND");
            problem(user.browser().write("PATCH", path(f.orgA, f.a2), update(0, "Forbidden", "UTC")), 404, "RESOURCE_NOT_FOUND");
            problem(user.browser().get(path(f.orgA, f.b1)), 404, "RESOURCE_NOT_FOUND");
        }
        problem(manager.write("POST", path(f.orgA, f.a1) + "/archive", version(1)), 403, "ACCESS_DENIED");
        problem(f.ownerA.browser().get(base(f.orgB)), 404, "RESOURCE_NOT_FOUND");
        problem(f.ownerA.browser().write("POST", base(f.orgB), create(UUID.randomUUID(), "FORBIDDEN", "Forbidden", "UTC")), 404, "RESOURCE_NOT_FOUND");
        problem(f.ownerA.browser().write("PATCH", path(f.orgA, f.b1), update(0, "Forbidden", "UTC")), 404, "RESOURCE_NOT_FOUND");
        assertThat(audits("STORE_UPDATED")).isEqualTo(1);
    }

    @Test
    void listsSearchAndPaginationRevealOnlyCurrentActorScopeWithoutForeignCounts() throws Exception {
        var owner = f.ownerA.browser();
        var page = tree(owner.get(base(f.orgA) + "?limit=1"));
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        assertThat(page.path("canCreate").asBoolean()).isTrue();
        var manager = tree(f.manager.browser().get(base(f.orgA) + "?limit=1&state=ALL&organizationId=" + f.orgB + "&actorId=" + f.ownerB.id));
        assertThat(manager.path("items")).hasSize(1);
        assertThat(manager.path("hasMore").asBoolean()).isFalse();
        assertThat(manager.path("canCreate").asBoolean()).isFalse();
        assertThat(manager.toString()).doesNotContain(f.a2.toString(), f.b1.toString(), f.b2.toString(), "total");
        assertThat(tree(f.manager.browser().get(base(f.orgA) + "?offset=1")).path("items").isEmpty()).isTrue();
        assertThat(tree(f.unassigned.browser().get(base(f.orgA))).path("items").isEmpty()).isTrue();
        assertThat(tree(owner.get(base(f.orgA) + "?q=Foreign")).path("items").isEmpty()).isTrue();
        assertThat(tree(owner.get(base(f.orgA) + "?q=%27%20OR%201%3D1%20--")).path("items").isEmpty()).isTrue();
        assertThat(tree(owner.get(base(f.orgA) + "?q=%25")).path("items").isEmpty()).isTrue();
        assertThat(tree(owner.get(base(f.orgA) + "?q=north")).path("items")).hasSize(1);
        for (var query : List.of("?limit=101", "?limit=0", "?offset=-1", "?state=UNKNOWN", "?q=" + "x".repeat(101))) problem(owner.get(base(f.orgA) + query), 400, "INVALID_REQUEST");
        var unknown = owner.get(path(f.orgA, UUID.randomUUID()));
        var foreign = owner.get(path(f.orgA, f.b1));
        assertThat(tree(unknown).path("detail")).isEqualTo(tree(foreign).path("detail"));
    }

    @Test
    void concurrentEditsReturnOneVersionConflictWithoutDeadlockOrLostHistory() throws Exception {
        var one = f.ownerA.browser();
        var two = new Browser(f.ownerA);
        var results = concurrent(() -> one.write("PATCH", path(f.orgA, f.a1), update(0, "First edit", "UTC")),
                () -> two.write("PATCH", path(f.orgA, f.a1), update(0, "Second edit", "Europe/Berlin")));
        assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        var saved = tree(one.get(path(f.orgA, f.a1)));
        assertThat(saved.path("version").asLong()).isEqualTo(1);
        assertThat(saved.path("name").asString()).isIn("First edit", "Second edit");
        assertThat(saved.path("code").asString()).isEqualTo("NORTH");
        assertThat(audits("STORE_UPDATED")).isEqualTo(1);
        problem(one.write("PATCH", path(f.orgA, f.a1), update(0, "Stale edit", "UTC")), 409, "STORE_CONFLICT");
        var forged = update(1, "Changed code", "UTC");
        forged.put("code", "REPLACED");
        problem(one.write("PATCH", path(f.orgA, f.a1), forged), 400, "INVALID_REQUEST");
    }

    @Test
    void deactivationPreservesIdentityAndHistoryButBlocksNewOperationsAndCodeReuse() throws Exception {
        var owner = f.ownerA.browser();
        var before = tree(owner.get(path(f.orgA, f.a1)));
        var archived = owner.write("POST", path(f.orgA, f.a1) + "/archive", version(0));
        assertThat(archived.statusCode()).isEqualTo(200);
        var after = tree(archived);
        assertThat(after.path("id")).isEqualTo(before.path("id"));
        assertThat(after.path("createdAt")).isEqualTo(before.path("createdAt"));
        assertThat(after.path("active").asBoolean()).isFalse();
        assertThat(after.path("allowedActions").toString()).isEqualTo("[\"READ_STORE\"]");
        assertThat(tree(owner.get(base(f.orgA) + "?state=ARCHIVED")).path("items")).hasSize(1);
        assertThat(tree(owner.get(base(f.orgA) + "?state=ALL")).path("items")).hasSize(2);
        problem(owner.write("PATCH", path(f.orgA, f.a1), update(1, "Forbidden", "UTC")), 409, "STORE_INACTIVE");
        problem(owner.write("POST", path(f.orgA, f.a1) + "/archive", version(1)), 409, "STORE_INACTIVE");
        problem(owner.write("POST", base(f.orgA), create(UUID.randomUUID(), "north", "Reused", "UTC")), 409, "STORE_CONFLICT");
        problem(f.manager.browser().get(path(f.orgA, f.a1)), 404, "RESOURCE_NOT_FOUND");
        problem(f.manager.browser().write("PATCH", path(f.orgA, f.a1), update(1, "Forbidden", "UTC")), 404, "RESOURCE_NOT_FOUND");
        assertThat(tree(f.manager.browser().get(base(f.orgA) + "?state=ALL")).path("items").isEmpty()).isTrue();
        assertThat(f.manager.browser().write("POST", "/api/v1/auth/active-organization", Map.of("organizationId", f.orgA)).statusCode()).isEqualTo(200);
        assertThat(tree(f.manager.browser().get("/api/v1/auth/me/effective-permissions")).path("storeGrants").isEmpty()).isTrue();
        var member = f.membership(f.unassigned);
        problem(owner.write("PUT", "/api/v1/organizations/" + f.orgA + "/members/" + member + "/store-grants/" + f.a1,
                Map.of("role", "STORE_MANAGER", "memberVersion", 0, "reason", REASON)), 404, "RESOURCE_NOT_FOUND");
        assertThat(audits("STORE_ARCHIVED")).isEqualTo(1);
    }

    @Test
    void namedTimezonesRetainDstRulesAndMalformedOrForgedMetadataIsRejected() throws Exception {
        var owner = f.ownerA.browser();
        var invalidReason = update(0, "Rejected edit", "UTC");
        invalidReason.put("reason", "Invalid\u0000reason");
        problem(owner.write("PATCH", path(f.orgA, f.a1), invalidReason), 400, "INVALID_REQUEST");
        assertThat(tree(owner.get(path(f.orgA, f.a1))).path("version").asLong()).isZero();
        for (var zone : List.of("CET", "+01:00", "UTC+01:00", "Europe/Invented", "right/Europe/Warsaw"))
            problem(owner.write("POST", base(f.orgA), create(UUID.randomUUID(), "BADZONE", "Bad timezone", zone)), 400, "INVALID_REQUEST");
        for (var code : List.of("BAD CODE", "../NORTH", "ß")) problem(owner.write("POST", base(f.orgA), create(UUID.randomUUID(), code, "Invalid code", "UTC")), 400, "INVALID_REQUEST");
        var invalidCountry = create(UUID.randomUUID(), "BADCOUNTRY", "Invalid country", "UTC");
        invalidCountry.put("countryCode", "XX");
        problem(owner.write("POST", base(f.orgA), invalidCountry), 400, "INVALID_REQUEST");
        var result = owner.write("POST", base(f.orgA), create(UUID.randomUUID(), "DST", "Timezone evidence", "Europe/Warsaw"));
        assertThat(result.statusCode()).isEqualTo(201);
        var zone = ZoneId.of(tree(result).path("zoneId").asString());
        assertThat(Instant.parse("2026-03-29T00:59:00Z").atZone(zone).getHour()).isEqualTo(1);
        assertThat(Instant.parse("2026-03-29T01:00:00Z").atZone(zone).getHour()).isEqualTo(3);
        assertThat(Instant.parse("2026-10-25T00:59:00Z").atZone(zone).getOffset().getTotalSeconds()).isEqualTo(7200);
        assertThat(Instant.parse("2026-10-25T01:00:00Z").atZone(zone).getOffset().getTotalSeconds()).isEqualTo(3600);
        var edit = update(0, "Forged active state", "UTC");
        edit.put("active", false);
        problem(owner.write("PATCH", path(f.orgA, f.a1), edit), 400, "INVALID_REQUEST");
        problem(owner.send("POST", base(f.orgA), json.writeValueAsString(create(UUID.randomUUID(), "NO_CSRF", "Denied", "UTC")), null), 403, "CSRF_INVALID");
        assertThat(audits("STORE_CREATED")).isEqualTo(1);
    }

    @Test
    void anyStoreAuditFailureRollsBackDataVersionAndCreationRetryState() throws Exception {
        var owner = f.ownerA.browser();
        var before = tree(owner.get(path(f.orgA, f.a1)));
        doThrow(new IllegalStateException("Test store audit failure")).when(audit).securityChange(any(), eq(f.orgA), eq("store"), any(), anyString(), anyMap(), anyString());
        var request = UUID.randomUUID();
        problem(owner.write("POST", base(f.orgA), create(request, "ROLLBACK", "Rolled back", "UTC")), 500, "INTERNAL_ERROR");
        problem(owner.write("PATCH", path(f.orgA, f.a1), update(0, "Rolled back", "UTC")), 500, "INTERNAL_ERROR");
        problem(owner.write("POST", path(f.orgA, f.a1) + "/archive", version(0)), 500, "INTERNAL_ERROR");
        assertThat(tree(owner.get(path(f.orgA, f.a1)))).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.store_creation_request WHERE org_id=:org").param("org", f.orgA).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.store WHERE org_id=:org").param("org", f.orgA).query(Long.class).single()).isEqualTo(2);
        doCallRealMethod().when(audit).securityChange(any(), eq(f.orgA), eq("store"), any(), anyString(), anyMap(), anyString());
        assertThat(owner.write("POST", base(f.orgA), create(request, "ROLLBACK", "Confirmed", "UTC")).statusCode()).isEqualTo(201);
    }

    @Test void aCommittedGrantRevocationRejectsTheStoreEditWaitingForAuthorization() throws Exception { orderedRevocation(false); }
    @Test void anAuthorizedStoreEditCommitsBeforeTheWaitingGrantRevocation() throws Exception { orderedRevocation(true); }

    private void orderedRevocation(boolean writeFirst) throws Exception {
        var manager = f.manager.browser();
        var owner = f.ownerA.browser();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var action = writeFirst ? "STORE_UPDATED" : "STORE_GRANT_REVOKED";
        doAnswer(call -> { entered.countDown(); if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test authorization barrier timed out"); return call.callRealMethod(); })
                .when(audit).securityChange(any(), eq(f.orgA), any(), any(), eq(action), anyMap(), anyString());
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<HttpResponse<String>> write = () -> manager.write("PATCH", path(f.orgA, f.a1), update(0, "Authorized metadata change", "UTC"));
            java.util.concurrent.Callable<HttpResponse<String>> revoke = () -> owner.write("DELETE", "/api/v1/organizations/" + f.orgA + "/members/" + f.membership(f.manager) + "/store-grants/" + f.a1,
                    Map.of("memberVersion", 0, "grantVersion", 0, "reason", REASON));
            var first = workers.submit(writeFirst ? write : revoke);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(writeFirst ? revoke : write);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%stockrescue.organization%'").query(Long.class).single()).isPositive());
            release.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(second.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(writeFirst ? 200 : 404);
        } finally { release.countDown(); }
        problem(manager.write("PATCH", path(f.orgA, f.a1), update(writeFirst ? 1 : 0, "Forbidden late edit", "UTC")), 404, "RESOURCE_NOT_FOUND");
        assertThat(audits("STORE_UPDATED")).isEqualTo(writeFirst ? 1 : 0);
    }

    private long audits(String action) { return jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action=:action").param("org", f.orgA).param("action", action).query(Long.class).single(); }
    private Map<String, Object> create(UUID request, String code, String name, String zone) { return new HashMap<>(Map.of("requestId", request, "code", code, "name", name, "addressLine1", "", "countryCode", "pl", "zoneId", zone)); }
    private Map<String, Object> update(long version, String name, String zone) { return new HashMap<>(Map.of("version", version, "name", name, "zoneId", zone, "reason", REASON)); }
    private Map<String, Object> version(long version) { return Map.of("version", version, "reason", REASON); }
    private String base(UUID org) { return "/api/v1/organizations/" + org + "/stores"; }
    private String path(UUID org, UUID store) { return base(org) + "/" + store; }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(tree(response).path("code").asString()).isEqualTo(code);
        assertThat(tree(response).path("correlationId").asString()).isEqualTo(response.headers().firstValue("X-Correlation-ID").orElseThrow());
    }
    private List<HttpResponse<String>> concurrent(java.util.concurrent.Callable<HttpResponse<String>> one, java.util.concurrent.Callable<HttpResponse<String>> two) throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); return one.call(); });
            var b = workers.submit(() -> { start.await(); return two.call(); });
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }
    private class Fixture {
        final User ownerA = user(), ownerB = user(), manager = user(), operator = user(), analyst = user(), unassigned = user();
        final UUID orgA = org(ownerA), orgB = org(ownerB);
        final UUID a1 = store(orgA, "NORTH", "North store"), a2 = store(orgA, "EAST", "East store"), b1 = store(orgB, "NORTH", "Foreign North"), b2 = store(orgB, "EAST", "Foreign East");
        Fixture() {
            member(ownerA, orgA, "ORG_ADMIN", null); member(ownerB, orgB, "ORG_ADMIN", null); member(unassigned, orgA, "MEMBER", null);
            member(manager, orgA, "MEMBER", "STORE_MANAGER"); member(operator, orgA, "MEMBER", "INVENTORY_OPERATOR"); member(analyst, orgA, "MEMBER", "READ_ONLY_ANALYST");
        }
        User user() {
            var user = new User(UUID.randomUUID(), UUID.randomUUID() + "@example.test");
            jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)").param("id", user.id).param("email", user.email).update();
            jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)").param("id", user.id).param("hash", hash).update();
            return user;
        }
        UUID org(User owner) { var id = UUID.randomUUID(); jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Store workflow cooperative',:owner)").param("id", id).param("owner", owner.id).update(); return id; }
        UUID store(UUID org, String code, String name) { var id = UUID.randomUUID(); jdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:id,:org,:code,:name,'Europe/Warsaw')").param("id", id).param("org", org).param("code", code).param("name", name).update(); return id; }
        void member(User user, UUID org, String role, String grantRole) {
            var id = UUID.randomUUID(); jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:user,:role)").param("id", id).param("org", org).param("user", user.id).param("role", role).update();
            if (grantRole != null) jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:member,:store,:role)").param("id", UUID.randomUUID()).param("org", org).param("member", id).param("store", a1).param("role", grantRole).update();
        }
        UUID membership(User user) { return jdbc.sql("SELECT id FROM stockrescue.membership WHERE org_id=:org AND user_id=:user").param("org", orgA).param("user", user.id).query(UUID.class).single(); }
    }
    private class User {
        final UUID id; final String email; private Browser browser;
        User(UUID id, String email) { this.id = id; this.email = email; }
        Browser browser() throws Exception { if (browser == null) browser = new Browser(this); return browser; }
    }
    private class Browser {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        Browser(User user) throws Exception { assertThat(write("POST", "/api/v1/auth/sign-in", Map.of("email", user.email, "password", PASSWORD)).statusCode()).isEqualTo(200); }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, "", null); }
        HttpResponse<String> write(String method, String path, Object body) throws Exception { var token = tree(get("/api/v1/auth/csrf")).path("token").asString(); return send(method, path, json.writeValueAsString(body), token); }
        HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json");
            if (token != null) request.header("X-CSRF-TOKEN", token);
            return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
