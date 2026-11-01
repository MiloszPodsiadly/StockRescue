package com.stockrescue.identity.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.stockrescue.platform.security.WebSessions;
import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doCallRealMethod;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@ExtendWith(OutputCaptureExtension.class)
class AccountAuthenticationTest extends InfrastructureTestBase {
    private static final String PASSWORD = "  a-strong-test-password  ";
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcClient jdbc;
    @MockitoSpyBean private FindByIndexNameSessionRepository<? extends Session> sessions;
    @MockitoSpyBean private PasswordEncoder encoder;

    @Test
    void registrationPersistsAnUnprivilegedAccountAndDoesNotEnumerateEmail(CapturedOutput logs) throws Exception {
        var browser = new Browser();
        var email = email();
        var first = browser.post("sign-up", credentials(email.toUpperCase(), PASSWORD));
        var duplicate = browser.post("sign-up", credentials(email, "another-strong-password"));
        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(duplicate.statusCode()).isEqualTo(202);
        assertThat(duplicate.body()).isEqualTo(first.body());
        assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
        var id = jdbc.sql("SELECT id FROM stockrescue.user_account WHERE email_normalized=:email").param("email", email).query(UUID.class).single();
        var hash = jdbc.sql("SELECT password_hash FROM stockrescue.credential WHERE user_id=:id").param("id", id).query(String.class).single();
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        assertThat(encoder.matches(PASSWORD.strip(), hash)).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.membership WHERE user_id=:id").param("id", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE actor_user_id=:id AND action='ACCOUNT_REGISTERED'").param("id", id).query(Long.class).single()).isEqualTo(1);
        assertThat(logs.getAll()).doesNotContain(PASSWORD, hash, email);
        for (var secret : browser.observedSecrets) assertThat(logs.getAll()).doesNotContain(secret);
    }

    @Test
    void loginRotatesTheSessionAndLogoutRejectsItsReplay(CapturedOutput logs) throws Exception {
        var browser = new Browser();
        var email = email();
        browser.post("sign-up", credentials(email, PASSWORD));
        var anonymousCookie = browser.cookie();
        var oldCsrf = browser.token();
        var login = browser.send("POST", "/api/v1/auth/sign-in", credentials(email, PASSWORD), oldCsrf);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(browser.cookie()).isNotEqualTo(anonymousCookie);
        assertThat(replay(anonymousCookie).statusCode()).isEqualTo(401);
        assertThat(browser.send("POST", "/api/v1/auth/sign-out", "", oldCsrf).statusCode()).isEqualTo(403);
        assertThat(mapper.readTree(browser.get("/api/v1/auth/me").body()).path("email").asString()).isEqualTo(email);
        var authenticatedCookie = browser.cookie();
        assertThat(browser.post("sign-out", "").statusCode()).isEqualTo(204);
        assertThat(replay(authenticatedCookie).statusCode()).isEqualTo(401);
        assertThat(logs.getAll()).doesNotContain(PASSWORD, email);
        for (var secret : browser.observedSecrets) assertThat(logs.getAll()).doesNotContain(secret);
    }

    @Test
    void concurrentRegistrationCreatesOnlyOneAccountCredentialAndAudit() throws Exception {
        var first = new Browser();
        var second = new Browser();
        var email = email();
        first.token(); second.token();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> first.post("sign-up", credentials(email, PASSWORD)));
            var two = workers.submit(() -> second.post("sign-up", credentials(email.toUpperCase(), PASSWORD)));
            assertThat(one.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(202);
            assertThat(two.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(202);
        }
        var user = userId(email);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.credential WHERE user_id=:id").param("id", user).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE actor_user_id=:id AND action='ACCOUNT_REGISTERED'").param("id", user).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void unsafeRequestsRequireTheCorrectSessionBoundCsrfAndExpiredTokensFail() throws Exception {
        var first = new Browser();
        var second = new Browser();
        var email = email();
        var body = credentials(email, PASSWORD);
        var token = first.token();
        second.token();
        for (var response : new HttpResponse[]{
                first.send("POST", "/api/v1/auth/sign-up", body, null),
                first.send("POST", "/api/v1/auth/sign-up", body, "wrong-token"),
                second.send("POST", "/api/v1/auth/sign-up", body, token)}) {
            assertThat(response.statusCode()).isEqualTo(403);
        }
        assertThat(first.post("sign-up", body).statusCode()).isEqualTo(202);
        assertThat(first.post("sign-in", body).statusCode()).isEqualTo(200);
        var expiredToken = first.token();
        jdbc.sql("UPDATE stockrescue.spring_session SET creation_time=:past-3600000,last_access_time=:past-3600000,expiry_time=:past WHERE principal_name=:id")
                .param("past", System.currentTimeMillis() - 1000).param("id", userId(email).toString()).update();
        assertThat(first.send("POST", "/api/v1/auth/sign-out", "", expiredToken).statusCode()).isEqualTo(403);
        assertThat(first.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void wrongMissingDisabledAndThrottledAccountsUseTheSameFailureContract() throws Exception {
        var browser = new Browser();
        var email = email();
        browser.post("sign-up", credentials(email, PASSWORD));
        var unknown = problem(browser.post("sign-in", credentials(email(), PASSWORD)));
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(problem(browser.post("sign-in", credentials(email, "incorrect-test-password")))).isEqualTo(unknown);
        }
        assertThat(problem(browser.post("sign-in", credentials(email, PASSWORD)))).isEqualTo(unknown);
        assertThat(jdbc.sql("SELECT failed_attempt_count FROM stockrescue.credential WHERE user_id=:id").param("id", userId(email)).query(Integer.class).single()).isEqualTo(5);
        jdbc.sql("UPDATE stockrescue.credential SET locked_until=now()-interval '1 minute',failure_window_started_at=now()-interval '1 hour' WHERE user_id=:id")
                .param("id", userId(email)).update();
        assertThat(browser.post("sign-in", credentials(email, PASSWORD)).statusCode()).isEqualTo(200);
        assertThat(browser.post("sign-out", "").statusCode()).isEqualTo(204);
        jdbc.sql("UPDATE stockrescue.user_account SET status='DISABLED' WHERE id=:id").param("id", userId(email)).update();
        assertThat(problem(browser.post("sign-in", credentials(email, PASSWORD)))).isEqualTo(unknown);
    }

    @Test
    void idleAbsoluteAndDisabledAccountExpiryRejectPersistedSessions() throws Exception {
        var browser = new Browser();
        var email = email();
        browser.post("sign-up", credentials(email, PASSWORD));
        browser.post("sign-in", credentials(email, PASSWORD));
        var user = userId(email);
        ageSession(sessions, user);
        assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
        browser.post("sign-in", credentials(email, PASSWORD));
        jdbc.sql("UPDATE stockrescue.user_account SET status='DISABLED' WHERE id=:id").param("id", user).update();
        assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
        jdbc.sql("UPDATE stockrescue.user_account SET status='ACTIVE' WHERE id=:id").param("id", user).update();
        assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
    }

    private <S extends Session> void ageSession(FindByIndexNameSessionRepository<S> repository, UUID user) {
        var persisted = repository.findByPrincipalName(user.toString());
        assertThat(persisted).hasSize(1);
        var session = persisted.values().iterator().next();
        session.setAttribute(WebSessions.AUTHENTICATED_AT, System.currentTimeMillis() - Duration.ofHours(13).toMillis());
        repository.save(session);
    }

    @Test
    void anonymousAndAuthenticatedAccountsCannotAccessUnlistedResources() throws Exception {
        var browser = new Browser();
        for (var path : new String[]{"/api/v1/auth/me", "/api/v1/organizations", "/api/env", "/v3/api-docs", "/api/health/unknown"}) {
            assertThat(browser.get(path).statusCode()).isEqualTo(401);
        }
        assertThat(browser.get("/api/health/liveness").statusCode()).isEqualTo(200);
        var email = email();
        browser.post("sign-up", credentials(email, PASSWORD));
        browser.post("sign-in", credentials(email, PASSWORD));
        var denied = browser.get("/api/v1/organizations/" + UUID.randomUUID() + "/unsupported");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(mapper.readTree(denied.body()).path("code").asString()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void invalidJsonOversizedPasswordsAndForgedRolesNeverCreateAccounts() throws Exception {
        var browser = new Browser();
        for (var password : new String[]{"short", "a".repeat(73), "ą".repeat(40)}) {
            assertThat(browser.post("sign-up", credentials(email(), password)).statusCode()).isEqualTo(400);
        }
        assertThat(browser.post("sign-up", "{broken").statusCode()).isEqualTo(400);
        var forged = mapper.writeValueAsString(Map.of("email", email(), "password", PASSWORD, "role", "ORG_ADMIN"));
        assertThat(browser.post("sign-up", forged).statusCode()).isEqualTo(400);
        var huge = browser.send("POST", "/api/v1/auth/sign-up", "x".repeat(5000), browser.token());
        assertThat(huge.statusCode()).isEqualTo(413);
        assertThat(huge.body()).contains("REQUEST_TOO_LARGE").doesNotContain("xxxxx");
        var chunked = HttpRequest.newBuilder(uri("/api/v1/auth/sign-up"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new java.io.ByteArrayInputStream(new byte[5000]))).build();
        assertThat(HttpClient.newHttpClient().send(chunked, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
    }

    @Test
    void deniedAnonymousWritesDoNotCreatePersistentSessions() throws Exception {
        var before = new HashSet<>(jdbc.sql("SELECT primary_id FROM stockrescue.spring_session").query(String.class).list());
        for (var path : new String[]{"/api/v1/not-an-endpoint", "/api/v1/auth/sign-in"}) {
            var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(credentials(email(), PASSWORD))).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }
        var after = jdbc.sql("SELECT primary_id FROM stockrescue.spring_session").query(String.class).list();
        assertThat(before).containsAll(after);
    }

    @Test
    void passwordHashingAdmissionIsBoundedAcrossUnknownAccounts() throws Exception {
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var password = "parallel-test-password";
        doAnswer(invocation -> {
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Hashing test timed out");
            return invocation.callRealMethod();
        }).when(encoder).matches(eq(password), anyString());
        var first = new Browser();
        var second = new Browser();
        var third = new Browser();
        first.token(); second.token(); third.token();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> first.post("sign-in", credentials(email(), password)));
            var two = workers.submit(() -> second.post("sign-in", credentials(email(), password)));
            try {
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                var busy = third.post("sign-in", credentials(email(), PASSWORD));
                assertThat(busy.statusCode()).isEqualTo(429);
                assertThat(busy.body()).contains("AUTH_RATE_LIMITED");
            } finally {
                release.countDown();
            }
            assertThat(one.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(401);
            assertThat(two.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void localCookieIsHttpOnlyAndForwardedHeadersCannotChangeItsSecurityMode() throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/auth/csrf"))
                .header("X-Forwarded-Proto", "https").header("Forwarded", "proto=https;host=attacker.test").GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        var cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Secure", "Domain=");
        assertThat(response.headers().firstValue("Strict-Transport-Security")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    @Test
    void unavailableSessionStorageReturnsASafeErrorAndRecoversWithoutLosingIdentity(CapturedOutput logs) throws Exception {
        var browser = new Browser();
        var email = email();
        browser.post("sign-up", credentials(email, PASSWORD));
        browser.post("sign-in", credentials(email, PASSWORD));
        for (var exception : new RuntimeException[]{new DataAccessResourceFailureException("private-session-diagnostic"),
                new CannotCreateTransactionException("private-session-diagnostic"), new TransactionSystemException("private-session-diagnostic")}) {
            doThrow(exception).when(sessions).findById(anyString());
            try {
                var failure = browser.get("/api/v1/auth/me");
                assertThat(failure.statusCode()).isEqualTo(503);
                assertThat(failure.body()).contains("SERVICE_UNAVAILABLE").doesNotContain("private-session-diagnostic", email);
                assertThat(logs.getAll()).doesNotContain("private-session-diagnostic", PASSWORD, email);
            } finally {
                doCallRealMethod().when(sessions).findById(anyString());
            }
        }
        assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(200);
    }

    private JsonNode problem(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        var body = mapper.readTree(response.body());
        assertThat(body.path("correlationId").asString()).isEqualTo(response.headers().firstValue("X-Correlation-ID").orElseThrow());
        return mapper.readTree(mapper.writeValueAsString(Map.of("status", body.path("status").asInt(),
                "code", body.path("code").asString(), "detail", body.path("detail").asString())));
    }

    private UUID userId(String email) { return jdbc.sql("SELECT id FROM stockrescue.user_account WHERE email_normalized=:email").param("email", email).query(UUID.class).single(); }
    private String email() { return UUID.randomUUID() + "@example.test"; }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private String credentials(String email, String password) { return mapper.writeValueAsString(Map.of("email", email, "password", password)); }
    private HttpResponse<String> replay(String cookie) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/v1/auth/me")).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private class Browser {
        private final List<String> observedSecrets = new ArrayList<>();
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, "", null); }
        String token() throws Exception {
            var response = get("/api/v1/auth/csrf");
            assertThat(response.statusCode()).isEqualTo(200);
            var token = mapper.readTree(response.body()).path("token").asString();
            observedSecrets.add(token);
            return token;
        }
        HttpResponse<String> post(String action, String body) throws Exception { return send("POST", "/api/v1/auth/" + action, body, token()); }
        String cookie() { return "SRSESSION=" + cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("SRSESSION")).findFirst().orElseThrow().getValue(); }
        HttpResponse<String> send(String method, String path, String body, String csrf) throws Exception {
            var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
            if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
            var response = client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            response.headers().allValues("Set-Cookie").forEach(header -> {
                var cookie = header.split(";", 2)[0];
                if (cookie.length() > "SRSESSION=".length()) observedSecrets.add(cookie);
            });
            return response;
        }
    }
}
