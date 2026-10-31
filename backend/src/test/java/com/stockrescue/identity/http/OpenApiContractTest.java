package com.stockrescue.identity.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"local", "contract"})
class OpenApiContractTest extends InfrastructureTestBase {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;

    @Test
    void reviewedContractMatchesBackendRoutesAndSchemas() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs.yaml"))
                .timeout(Duration.ofSeconds(30)).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var contract = response.body().replace("\r\n", "\n");
        var path = Path.of("../contracts/http/openapi.yaml");
        if (Boolean.getBoolean("updateApiContract")) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, contract, StandardCharsets.UTF_8);
        } else {
            assertThat(contract).isEqualTo(Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
        assertThat(contract).contains("/api/v1/auth/sign-in", "/api/v1/auth/sign-up", "X-CSRF-TOKEN", "SRSESSION")
                .contains("CsrfResponse:", "UserResponse:", "RegistrationResponse:", "Credentials:")
                .contains("/api/v1/organizations:", "/api/v1/organizations/{organizationId}:",
                        "/api/v1/auth/active-organization:", "/api/v1/auth/me/effective-permissions:", "OnboardingRequest:", "PermissionsResponse:")
                .doesNotContain("/api/health", "password_hash");
        var schemaRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build();
        var document=json.readTree(HttpClient.newHttpClient().send(schemaRequest,HttpResponse.BodyHandlers.ofString()).body());
        var schemas=document.path("components").path("schemas");
        for (var field : java.util.Map.of("LaneResponse", "currentCost", "LaneLookupResponse", "lane").entrySet()) {
            var union = schemas.path(field.getKey()).path("properties").path(field.getValue()).path("anyOf");
            assertThat(union).hasSize(2);
            assertThat(union.get(0).path("$ref").asString()).startsWith("#/components/schemas/");
            assertThat(union.get(1).path("type").asString()).isEqualTo("null");
        }
        var temperature = schemas.path("RevisionResponse").path("properties").path("temperatureClass");
        assertThat(temperature.path("enum")).anyMatch(value -> value.isNull());
        var cancellationPath="/api/v1/organizations/{organizationId}/transport-lanes/{laneId}/scheduled-revisions/cancel";
        assertThat(schemas.path("RevisionResponse").path("properties").path("cancellation").path("anyOf")).hasSize(2);
        var cancellationProperties=schemas.path("CancelledRevisionResponse").path("properties");
        for(var field:java.util.List.of("id","revisionId","revision","cancelledByMembershipId","reason","cancelledAt"))
            assertThat(cancellationProperties.has(field)).isTrue();
        var resultProperties=schemas.path("ScheduledRevisionCancellationResponse").path("properties");
        assertThat(resultProperties.has("lane")).isTrue();
        assertThat(resultProperties.has("cancelledRevisions")).isTrue();
        var operation=document.path("paths").path(cancellationPath).path("post");
        assertThat(operation.path("operationId").asString()).isEqualTo("cancelScheduledLaneRevisions");
        assertThat(operation.path("responses").has("200")).isTrue();
    }
}
