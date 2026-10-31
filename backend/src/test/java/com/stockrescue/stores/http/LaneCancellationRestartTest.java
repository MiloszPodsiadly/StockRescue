package com.stockrescue.stores.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestMethodOrder(OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LaneCancellationRestartTest extends InfrastructureTestBase {
    private static final String PASSWORD = "lane-cancellation-restart-passphrase";
    private static final String REASON = "Reviewed scheduled cost change";
    private static String email;
    private static UUID organization;
    private static UUID lane;

    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder encoder;

    @Test
    @Order(1)
    void cancelScheduledPriceBeforeRestart() throws Exception {
        var actor = UUID.randomUUID();
        email = actor + "@example.test";
        organization = UUID.randomUUID();
        var membership = UUID.randomUUID();
        var origin = UUID.randomUUID();
        var destination = UUID.randomUUID();
        var originZone = UUID.randomUUID();
        var destinationZone = UUID.randomUUID();

        jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)")
                .param("id", actor)
                .param("email", email)
                .update();
        jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)")
                .param("id", actor)
                .param("hash", encoder.encode(PASSWORD))
                .update();
        jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Lane cancellation restart',:actor)")
                .param("id", organization)
                .param("actor", actor)
                .update();
        jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:actor,'ORG_ADMIN')")
                .param("id", membership)
                .param("org", organization)
                .param("actor", actor)
                .update();
        jdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:a,:org,'ORIGIN','Origin','Europe/Warsaw'),(:b,:org,'DESTINATION','Destination','Europe/Warsaw')")
                .param("a", origin)
                .param("b", destination)
                .param("org", organization)
                .update();
        jdbc.sql("INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES (:a,:org,:origin,'Ambient','AMBIENT'),(:b,:org,:destination,'Ambient','AMBIENT')")
                .param("a", originZone)
                .param("b", destinationZone)
                .param("org", organization)
                .param("origin", origin)
                .param("destination", destination)
                .update();

        var owner = new Browser();
        var base = lanes();
        var created = owner.write("POST", base, Map.of(
                "originStoreId", origin,
                "destinationStoreId", destination,
                "originZoneId", originZone,
                "destinationZoneId", destinationZone,
                "reason", REASON));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        lane = UUID.fromString(tree(created).path("id").asString());

        var path = base + "/" + lane;
        var current = owner.write("POST", path + "/revisions", revision(0, 0, "12.0000", null));
        assertThat(current.statusCode()).as(current.body()).isEqualTo(201);
        var future = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        var planned = owner.write("POST", path + "/revisions", revision(1, 1, "13.0000", future));
        assertThat(planned.statusCode()).as(planned.body()).isEqualTo(201);

        var cancelled = owner.write("POST", path + "/scheduled-revisions/cancel", Map.of(
                "laneVersion", 2,
                "expectedRevision", 2,
                "reason", REASON));
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(tree(cancelled).path("cancelledRevisions")).hasSize(1);
        assertThat(tree(cancelled).path("lane").path("version").asLong()).isEqualTo(3);
    }

    @Test
    @Order(2)
    void cancellationEvidenceRemainsReadableAfterApplicationRestart() throws Exception {
        var owner = new Browser();
        var path = lanes() + "/" + lane;
        var detail = tree(owner.get(path));

        assertThat(detail.path("version").asLong()).isEqualTo(3);
        assertThat(detail.path("latestRevision").asLong()).isEqualTo(2);
        assertThat(detail.path("currentCost").path("costAmount").asString()).isEqualTo("12.0000");
        var history = tree(owner.get(path + "/revisions")).path("items");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).path("cancellation").path("revision").asLong()).isEqualTo(2);
        assertThat(history.get(0).path("cancellation").path("reason").asString()).isEqualTo(REASON);
        assertThat(history.get(0).path("cancellation").path("cancelledByMembershipId").asString()).isNotBlank();
        assertThat(Instant.parse(history.get(0).path("cancellation").path("cancelledAt").asString()))
                .isBefore(Instant.now());
        assertThat(history.get(0).path("validUntil").isNull()).isTrue();
        assertThat(history.get(1).path("cancellation").isNull()).isTrue();
    }

    private Map<String, Object> revision(long version, long latest, String amount, Instant effective) {
        var body = new HashMap<String, Object>(Map.of(
                "laneVersion", version,
                "expectedRevision", latest,
                "costAmount", amount,
                "currencyCode", "EUR",
                "durationMinutes", 45,
                "reason", REASON));
        if (effective != null) {
            body.put("effectiveAt", effective.toString());
        }
        return body;
    }

    private String lanes() {
        return "/api/v1/organizations/" + organization + "/transport-lanes";
    }

    private JsonNode tree(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    private final class Browser {
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();

        Browser() throws Exception {
            var response = write("POST", "/api/v1/auth/sign-in", Map.of("email", email, "password", PASSWORD));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }

        HttpResponse<String> get(String path) throws Exception {
            return send("GET", path, "", null);
        }

        HttpResponse<String> write(String method, String path, Object body) throws Exception {
            var token = tree(get("/api/v1/auth/csrf")).path("token").asString();
            return send(method, path, json.writeValueAsString(body), token);
        }

        HttpResponse<String> send(String method, String path, String body, String csrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json");
            if (csrf != null) {
                request.header("X-CSRF-TOKEN", csrf);
            }
            return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
