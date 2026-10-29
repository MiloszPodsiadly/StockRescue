package com.stockrescue.platform.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HttpFoundationTest.FailureEndpoint.class)
class HttpFoundationTest extends InfrastructureTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void healthProbesExposeOnlyStatusAndDiagnosticsStayPrivate() throws Exception {
        for (String path : new String[]{"/api/health", "/api/health/liveness", "/api/health/readiness"}) {
            var response = get(path);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"")
                    .doesNotContain("\"components\"", "\"details\"", "\"diskSpace\"");
            assertThat(response.headers().firstValue(CorrelationIdFilter.HEADER_NAME))
                    .hasValueSatisfying(value -> assertThat(value).matches("[a-f0-9-]{36}"));
        }
        for (String path : new String[]{"/api/env", "/api/configprops", "/api/beans", "/api"}) {
            assertThat(get(path).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void validCorrelationIsPreservedAndMalformedOrDuplicateHeadersAreRejected() throws Exception {
        var valid = get("/api/health", "developer-check_01");
        assertThat(valid.statusCode()).isEqualTo(200);
        assertThat(valid.headers().firstValue(CorrelationIdFilter.HEADER_NAME)).contains("developer-check_01");

        for (String value : new String[]{"unsafe value", "<unsafe>", "a".repeat(65), ""}) {
            var invalid = get("/api/health", value);
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(invalid.headers().firstValue("content-type"))
                    .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
            assertThat(invalid.headers().firstValue(CorrelationIdFilter.HEADER_NAME))
                    .hasValueSatisfying(generated -> assertThat(generated).isNotEqualTo(value));
            assertThat(invalid.body()).contains("\"status\":400").doesNotContain("<unsafe>");
        }
        var duplicate = get("/api/health", "first", "second");
        assertThat(duplicate.statusCode()).isEqualTo(400);
    }

    @Test
    void requestLogsAreStructuredCorrelatedAndDoNotExposeSecrets(CapturedOutput output) throws Exception {
        var missing = get("/api/does-not-exist");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.headers().firstValue("content-type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));

        var invalidCorrelation = "invalid correlation test secret";
        var rejected = get("/api/health", invalidCorrelation);
        assertThat(rejected.statusCode()).isEqualTo(400);
        var generatedCorrelationId = rejected.headers().firstValue(CorrelationIdFilter.HEADER_NAME).orElseThrow();
        assertThat(generatedCorrelationId).matches("[a-f0-9-]{36}");

        var failed = getWithSensitiveHeaders("/api/test-failure", "failure-check");
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(failed.body()).contains("\"status\":500", "The request could not be completed.")
                .doesNotContain(FailureEndpoint.TEST_SECRET, "IllegalStateException", "stackTrace");
        assertThat(failed.headers().firstValue(CorrelationIdFilter.HEADER_NAME)).contains("failure-check");

        assertApplicationLog(logRecord(output, "Rejected request with invalid or duplicate X-Correlation-ID"),
                "WARN", CorrelationIdFilter.class, generatedCorrelationId);
        assertApplicationLog(logRecord(output, "Request failed; exceptionType=java.lang.IllegalStateException"),
                "ERROR", ApiExceptionHandler.class, "failure-check");
        assertThat(output.getAll()).doesNotContain(invalidCorrelation, FailureEndpoint.TEST_SECRET);
    }

    private JsonNode logRecord(CapturedOutput output, String message) throws Exception {
        var matchingLines = output.getAll().lines().filter(line -> line.contains(message)).toList();
        assertThat(matchingLines).hasSize(1);
        return objectMapper.readTree(matchingLines.getFirst());
    }

    private void assertApplicationLog(JsonNode record, String level, Class<?> logger, String correlationId) {
        assertThat(record.path("@timestamp").asString()).isNotBlank();
        assertThat(record.path("level").asString()).isEqualTo(level);
        assertThat(record.path("logger_name").asString()).isEqualTo(logger.getName());
        assertThat(record.path("message").asString()).isNotBlank();
        assertThat(record.path("correlationId").asString()).isEqualTo(correlationId);
    }

    private HttpResponse<String> get(String path, String... correlationIds) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        for (String value : correlationIds) {
            request.header(CorrelationIdFilter.HEADER_NAME, value);
        }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithSensitiveHeaders(String path, String correlationId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header(CorrelationIdFilter.HEADER_NAME, correlationId)
                .header("Authorization", "Bearer " + FailureEndpoint.TEST_SECRET)
                .header("Cookie", "session=" + FailureEndpoint.TEST_SECRET);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @RestController
    static class FailureEndpoint {

        private static final String TEST_SECRET = "test-secret-must-not-appear-in-logs-7f1c2c";

        @GetMapping("/api/test-failure")
        void fail() {
            throw new IllegalStateException(TEST_SECRET);
        }
    }
}
