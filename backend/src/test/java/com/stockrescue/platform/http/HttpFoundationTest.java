package com.stockrescue.platform.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HttpFoundationTest.FailureEndpoint.class)
class HttpFoundationTest extends InfrastructureTestBase {

    @LocalServerPort
    private int port;

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
    void errorsUseProblemDetailsWithoutExposingExceptionMessages() throws Exception {
        var missing = get("/api/does-not-exist");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.headers().firstValue("content-type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));

        var failed = get("/api/test-failure", "failure-check");
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(failed.body()).contains("\"status\":500", "The request could not be completed.")
                .doesNotContain("private-diagnostic", "IllegalStateException", "stackTrace");
        assertThat(failed.headers().firstValue(CorrelationIdFilter.HEADER_NAME)).contains("failure-check");
    }

    private HttpResponse<String> get(String path, String... correlationIds) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        for (String value : correlationIds) {
            request.header(CorrelationIdFilter.HEADER_NAME, value);
        }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @RestController
    static class FailureEndpoint {

        @GetMapping("/api/test-failure")
        void fail() {
            throw new IllegalStateException("private-diagnostic");
        }
    }
}
