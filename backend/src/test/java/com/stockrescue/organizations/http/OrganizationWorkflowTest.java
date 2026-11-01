package com.stockrescue.organizations.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@ExtendWith(OutputCaptureExtension.class)
class OrganizationWorkflowTest extends InfrastructureTestBase {
    private static final String PASSWORD = "organization-owner-passphrase";
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private FindByIndexNameSessionRepository<? extends Session> sessions;
    @MockitoSpyBean private AuditWriter audit;

    @Test
    void emptyMembershipAndInvalidRequestsNeverGrantAuthority() throws Exception {
        var anonymous = new Browser();
        assertThat(anonymous.get("/api/v1/organizations").statusCode()).isEqualTo(401);
        var browser = registered();
        assertThat(tree(browser.get("/api/v1/organizations")).path("items").isEmpty()).isTrue();
        assertThat(browser.get("/api/v1/auth/active-organization").statusCode()).isEqualTo(204);
        problem(browser.get("/api/v1/auth/me/effective-permissions"), 403, "ORGANIZATION_REQUIRED");
        var body = onboarding(UUID.randomUUID(), "My organization");
        problem(browser.send("POST", "/api/v1/organizations", body, null, Map.of()), 403, "CSRF_INVALID");
        for (var invalid : List.of(
                mapper.writeValueAsString(Map.of("name", "Example")),
                mapper.writeValueAsString(Map.of("requestId", UUID.randomUUID(), "name", "  ")),
                mapper.writeValueAsString(Map.of("requestId", UUID.randomUUID(), "name", "x".repeat(161))),
                mapper.writeValueAsString(Map.of("requestId", UUID.randomUUID(), "name", "Invalid\u0000organization")),
                mapper.writeValueAsString(Map.of("requestId", UUID.randomUUID(), "name", "Example", "role", "ORG_ADMIN")),
                mapper.writeValueAsString(Map.of("requestId", UUID.randomUUID(), "name", "Example", "userId", UUID.randomUUID())))) {
            problem(browser.post("/api/v1/organizations", invalid), 400, "INVALID_REQUEST");
        }
        for (var query : List.of("?offset=-1", "?offset=10001", "?limit=0", "?limit=101", "?offset=invalid")) {
            problem(browser.get("/api/v1/organizations" + query), 400, "INVALID_REQUEST");
        }
        assertThat(count("organization", "created_by_user_id", browser.actor)).isZero();
        assertThat(count("membership", "user_id", browser.actor)).isZero();
    }

    @Test
    void onboardingCreatesOneDurableOwnerAndTwoCorrelatedAuditEntries(CapturedOutput logs) throws Exception {
        var browser = registered();
        var key = UUID.randomUUID();
        var name = "Private cooperative " + UUID.randomUUID();
        var response = browser.post("/api/v1/organizations", onboarding(key, "  " + name + "  "));
        assertThat(response.statusCode()).isEqualTo(201);
        var organization = tree(response);
        var id = UUID.fromString(organization.path("id").asString());
        assertThat(organization.path("name").asString()).isEqualTo(name);
        assertThat(organization.path("role").asString()).isEqualTo("ORG_ADMIN");
        assertThat(count("membership", "org_id", id)).isEqualTo(1);
        assertThat(count("organization_onboarding", "user_id", browser.actor)).isEqualTo(1);
        var entries = jdbc.sql("""
                SELECT actor_user_id,resource_type,action,changes->>'outcome' AS outcome,correlation_id,occurred_at
                FROM stockrescue.audit_entry WHERE org_id=:org ORDER BY action
                """).param("org", id).query().listOfRows();
        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(row -> {
            assertThat(row.get("actor_user_id")).isEqualTo(browser.actor);
            assertThat(row.get("outcome")).isEqualTo("SUCCEEDED");
            assertThat(row.get("correlation_id")).isEqualTo(response.headers().firstValue("X-Correlation-ID").orElseThrow());
            assertThat(row.get("occurred_at")).isNotNull();
        });
        assertThat(entries).extracting(row -> row.get("action")).containsExactly("INITIAL_OWNER_ASSIGNED", "ORGANIZATION_CREATED");
        var replay = browser.post("/api/v1/organizations", onboarding(key, name));
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(tree(replay)).isEqualTo(organization);
        assertThat(count("audit_entry", "org_id", id)).isEqualTo(2);
        problem(browser.post("/api/v1/organizations", onboarding(key, "Changed name")), 409, "ONBOARDING_CONFLICT");
        problem(browser.post("/api/v1/organizations", onboarding(UUID.randomUUID(), name)), 409, "ONBOARDING_CONFLICT");
        var freshSession = signedIn(browser.email);
        assertThat(tree(freshSession.get("/api/v1/organizations/" + id))).isEqualTo(organization);
        assertThat(freshSession.get("/api/v1/auth/active-organization").statusCode()).isEqualTo(204);
        assertThat(freshSession.select(id).statusCode()).isEqualTo(200);
        var permissions = tree(freshSession.get("/api/v1/auth/me/effective-permissions"));
        assertThat(permissions.path("organizationActions").toString()).contains("MANAGE_ACCESS", "CREATE_STORE");
        assertThat(permissions.path("allStoreActions").toString()).contains("ARCHIVE_STORE");
        assertThat(permissions.path("storeGrants").isEmpty()).isTrue();
        assertThat(logs.getAll()).doesNotContain(name, browser.email, PASSWORD);
    }

    @Test
    void concurrentRetriesConvergeWithoutDuplicatingOrganizationsOrOwnership() throws Exception {
        var first = registered();
        var second = signedIn(first.email);
        var key = UUID.randomUUID();
        var results = concurrent(first, onboarding(key, "Concurrent cooperative"), second, onboarding(key, "Concurrent cooperative"));
        assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 200);
        assertThat(tree(results.getFirst()).path("id")).isEqualTo(tree(results.getLast()).path("id"));
        assertThat(count("organization", "created_by_user_id", first.actor)).isEqualTo(1);
        assertThat(count("membership", "user_id", first.actor)).isEqualTo(1);
        var different = registered();
        var anotherSession = signedIn(different.email);
        var differentKeys = concurrent(different, onboarding(UUID.randomUUID(), "First request"),
                anotherSession, onboarding(UUID.randomUUID(), "Another request"));
        assertThat(differentKeys).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        assertThat(count("organization", "created_by_user_id", different.actor)).isEqualTo(1);
        assertThat(count("membership", "user_id", different.actor)).isEqualTo(1);
    }

    @Test
    void eitherAuditFailureRollsBackOrganizationOwnershipAndRetryRecord() throws Exception {
        var browser = registered();
        var key = UUID.randomUUID();
        for (var action : List.of("ORGANIZATION_CREATED", "INITIAL_OWNER_ASSIGNED")) {
            doThrow(new IllegalStateException("Test audit storage failure")).when(audit)
                    .organizationAction(eq(browser.actor), any(), any(), any(), eq(action));
            problem(browser.post("/api/v1/organizations", onboarding(key, "Atomic onboarding")), 500, "INTERNAL_ERROR");
            assertThat(count("organization", "created_by_user_id", browser.actor)).isZero();
            assertThat(count("membership", "user_id", browser.actor)).isZero();
            assertThat(count("organization_onboarding", "user_id", browser.actor)).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE actor_user_id=:actor AND org_id IS NOT NULL")
                    .param("actor", browser.actor).query(Long.class).single()).isZero();
            doCallRealMethod().when(audit).organizationAction(eq(browser.actor), any(), any(), any(), eq(action));
        }
        assertThat(browser.post("/api/v1/organizations", onboarding(key, "Atomic onboarding")).statusCode()).isEqualTo(201);
    }

    @Test
    void membershipsAndStoreGrantsAreIndependentAcrossOrganizationsAndReloadedOnEveryRequest() throws Exception {
        var actor = registered();
        var a = create(actor, "Organization A");
        var ownerB = registered();
        var b = create(ownerB, "Organization B");
        var outsider = registered();
        var foreign = create(outsider, "Foreign organization");
        var membershipB = addMember(actor.actor, b);
        var assigned = addStore(b, "Assigned store");
        var unassigned = addStore(b, "Hidden store");
        var grant = UUID.randomUUID();
        jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:member,:store,'STORE_MANAGER')")
                .param("id", grant).param("org", b).param("member", membershipB).param("store", assigned).update();
        var firstPage = tree(actor.get("/api/v1/organizations?limit=1"));
        var secondPage = tree(actor.get("/api/v1/organizations?limit=1&offset=1"));
        assertThat(firstPage.path("items")).hasSize(1);
        assertThat(firstPage.path("hasMore").asBoolean()).isTrue();
        assertThat(secondPage.path("items")).hasSize(1);
        assertThat(secondPage.path("hasMore").asBoolean()).isFalse();
        var forgedQuery = tree(actor.send("GET", "/api/v1/organizations?organizationId=" + foreign + "&userId=" + outsider.actor,
                "", null, Map.of("X-Organization-ID", foreign.toString())));
        assertThat(forgedQuery.path("items")).hasSize(2);
        assertThat(forgedQuery.toString()).doesNotContain(foreign.toString(), "Foreign organization", "total");
        assertThat(tree(actor.get("/api/v1/organizations?offset=2")).path("items").isEmpty()).isTrue();
        var missing = problem(actor.get("/api/v1/organizations/" + UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        var denied = problem(actor.get("/api/v1/organizations/" + foreign), 404, "RESOURCE_NOT_FOUND");
        assertThat(denied.path("detail")).isEqualTo(missing.path("detail"));
        assertThat(denied.toString()).doesNotContain(foreign.toString(), "Foreign organization");
        assertThat(actor.select(a).statusCode()).isEqualTo(200);
        problem(actor.select(foreign), 404, "RESOURCE_NOT_FOUND");
        assertThat(tree(actor.get("/api/v1/auth/active-organization")).path("id").asString()).isEqualTo(a.toString());
        assertThat(actor.select(b).statusCode()).isEqualTo(200);
        var manager = tree(actor.get("/api/v1/auth/me/effective-permissions"));
        assertThat(manager.path("organization").path("role").asString()).isEqualTo("MEMBER");
        assertThat(manager.path("organizationActions").toString()).isEqualTo("[\"READ_ORGANIZATION\"]");
        assertThat(manager.path("allStoreActions").isEmpty()).isTrue();
        assertThat(manager.path("storeGrants")).hasSize(1);
        assertThat(manager.path("storeGrants").get(0).path("actions").toString()).contains("EDIT_STORE").doesNotContain("ARCHIVE_STORE");
        assertThat(manager.toString()).doesNotContain(unassigned.toString(), foreign.toString());
        jdbc.sql("UPDATE stockrescue.store_grant SET role='READ_ONLY_ANALYST',version=version+1 WHERE id=:id").param("id", grant).update();
        var analyst = tree(actor.get("/api/v1/auth/me/effective-permissions"));
        assertThat(analyst.path("storeGrants").get(0).path("actions").toString()).isEqualTo("[\"READ_STORE\"]");
        jdbc.sql("UPDATE stockrescue.store_grant SET state='REVOKED',revoked_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=:id").param("id", grant).update();
        assertThat(tree(actor.get("/api/v1/auth/me/effective-permissions")).path("storeGrants").isEmpty()).isTrue();
        jdbc.sql("UPDATE stockrescue.membership SET state='REVOKED',revoked_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=:id").param("id", membershipB).update();
        problem(actor.get("/api/v1/auth/me/effective-permissions"), 404, "RESOURCE_NOT_FOUND");
        assertThat(actor.get("/api/v1/auth/active-organization").statusCode()).isEqualTo(204);
        problem(actor.get("/api/v1/organizations/" + b), 404, "RESOURCE_NOT_FOUND");
        problem(actor.select(b), 404, "RESOURCE_NOT_FOUND");
        assertThat(tree(actor.get("/api/v1/organizations")).path("items")).hasSize(1);
    }

    @Test
    void forgedSessionSelectionAndArchivedOrganizationCannotResurrectAuthority() throws Exception {
        var actor = registered();
        var key = UUID.randomUUID();
        var created = actor.post("/api/v1/organizations", onboarding(key, "Archivable organization"));
        var organization = UUID.fromString(tree(created).path("id").asString());
        var outsider = registered();
        var foreign = create(outsider, "Foreign organization");
        forgeSelection(sessions, actor.actor, foreign);
        problem(actor.get("/api/v1/auth/me/effective-permissions"), 404, "RESOURCE_NOT_FOUND");
        assertThat(actor.get("/api/v1/auth/active-organization").statusCode()).isEqualTo(204);
        assertThat(actor.select(organization).statusCode()).isEqualTo(200);
        jdbc.sql("UPDATE stockrescue.organization SET status='ARCHIVED',version=version+1 WHERE id=:org").param("org", organization).update();
        assertThat(actor.get("/api/v1/auth/active-organization").statusCode()).isEqualTo(204);
        problem(actor.get("/api/v1/organizations/" + organization), 404, "RESOURCE_NOT_FOUND");
        problem(actor.post("/api/v1/organizations", onboarding(key, "Archivable organization")), 404, "RESOURCE_NOT_FOUND");
        assertThat(tree(actor.get("/api/v1/organizations")).path("items").isEmpty()).isTrue();
        assertThat(count("membership", "org_id", organization)).isEqualTo(1);
        assertThat(count("audit_entry", "org_id", organization)).isEqualTo(2);
    }

    @Test
    void revokedOwnerCannotUseAnOldRetryToRestoreTheirMembership() throws Exception {
        var actor = registered();
        var key = UUID.randomUUID();
        var created = actor.post("/api/v1/organizations", onboarding(key, "Former organization"));
        var organization = UUID.fromString(tree(created).path("id").asString());
        jdbc.sql("UPDATE stockrescue.membership SET state='REVOKED',revoked_at=CURRENT_TIMESTAMP,version=version+1 WHERE org_id=:org")
                .param("org", organization).update();
        problem(actor.post("/api/v1/organizations", onboarding(key, "Former organization")), 404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT state FROM stockrescue.membership WHERE org_id=:org").param("org", organization).query(String.class).single()).isEqualTo("REVOKED");
        assertThat(count("audit_entry", "org_id", organization)).isEqualTo(2);
    }

    @Test
    void loginThrottleBlocksNewAuthenticationWhileDisablingTheAccountEndsExistingAccess() throws Exception {
        var actor = registered();
        jdbc.sql("UPDATE stockrescue.credential SET locked_until=CURRENT_TIMESTAMP+INTERVAL '15 minutes' WHERE user_id=:id").param("id", actor.actor).update();
        var blockedLogin = new Browser();
        problem(blockedLogin.post("/api/v1/auth/sign-in", credentials(actor.email)), 401, "INVALID_CREDENTIALS");
        assertThat(actor.get("/api/v1/organizations").statusCode()).isEqualTo(200);
        var token = tree(actor.get("/api/v1/auth/csrf")).path("token").asString();
        jdbc.sql("UPDATE stockrescue.user_account SET status='DISABLED' WHERE id=:id").param("id", actor.actor).update();
        problem(actor.send("POST", "/api/v1/organizations", onboarding(UUID.randomUUID(), "Unavailable organization"), token, Map.of()), 401, "SESSION_EXPIRED");
        assertThat(count("organization", "created_by_user_id", actor.actor)).isZero();
        assertThat(actor.get("/api/v1/organizations").statusCode()).isEqualTo(401);
    }

    private List<HttpResponse<String>> concurrent(Browser first, String one, Browser second, String two) throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); return first.post("/api/v1/organizations", one); });
            var b = workers.submit(() -> { start.await(); return second.post("/api/v1/organizations", two); });
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private <S extends Session> void forgeSelection(FindByIndexNameSessionRepository<S> repository, UUID actor, UUID organization) {
        var session = repository.findByPrincipalName(actor.toString()).values().iterator().next();
        session.setAttribute("activeOrganizationId", organization.toString());
        repository.save(session);
    }

    private UUID addMember(UUID actor, UUID organization) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:actor,'MEMBER')")
                .param("id", id).param("org", organization).param("actor", actor).update();
        return id;
    }

    private UUID addStore(UUID organization, String name) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO stockrescue.store(id,org_id,code,name,address_line1,city,postal_code,country_code,zone_id)
                VALUES (:id,:org,:code,:name,'Street 1','Warsaw','00-001','PL','Europe/Warsaw')
                """).param("id", id).param("org", organization).param("code", id.toString().replace("-", "")).param("name", name).update();
        return id;
    }

    private UUID create(Browser browser, String name) throws Exception {
        var response = browser.post("/api/v1/organizations", onboarding(UUID.randomUUID(), name));
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(tree(response).path("id").asString());
    }

    private Browser registered() throws Exception {
        var browser = new Browser();
        browser.email = UUID.randomUUID() + "@example.test";
        assertThat(browser.post("/api/v1/auth/sign-up", credentials(browser.email)).statusCode()).isEqualTo(202);
        browser.actor = UUID.fromString(tree(browser.post("/api/v1/auth/sign-in", credentials(browser.email))).path("id").asString());
        return browser;
    }

    private Browser signedIn(String email) throws Exception {
        var browser = new Browser();
        browser.email = email;
        var response = browser.post("/api/v1/auth/sign-in", credentials(email));
        assertThat(response.statusCode()).isEqualTo(200);
        browser.actor = UUID.fromString(tree(response).path("id").asString());
        return browser;
    }

    private long count(String table, String column, UUID id) {
        return jdbc.sql("SELECT count(*) FROM stockrescue." + table + " WHERE " + column + "=:id").param("id", id).query(Long.class).single();
    }
    private String credentials(String email) { return mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD)); }
    private String onboarding(UUID key, String name) { return mapper.writeValueAsString(Map.of("requestId", key, "name", name)); }
    private JsonNode tree(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    private JsonNode problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        var problem = tree(response);
        assertThat(problem.path("code").asString()).isEqualTo(code);
        assertThat(problem.path("correlationId").asString()).isEqualTo(response.headers().firstValue("X-Correlation-ID").orElseThrow());
        return problem;
    }

    private class Browser {
        private UUID actor;
        private String email;
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, "", null, Map.of()); }
        HttpResponse<String> post(String path, String body) throws Exception {
            var token = tree(get("/api/v1/auth/csrf")).path("token").asString();
            return send("POST", path, body, token, Map.of());
        }
        HttpResponse<String> select(UUID organization) throws Exception {
            return post("/api/v1/auth/active-organization", mapper.writeValueAsString(Map.of("organizationId", organization)));
        }
        HttpResponse<String> send(String method, String path, String body, String csrf, Map<String, String> headers) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json");
            if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
            headers.forEach(request::header);
            return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
