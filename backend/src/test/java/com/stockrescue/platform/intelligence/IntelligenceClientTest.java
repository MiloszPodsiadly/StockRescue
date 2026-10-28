package com.stockrescue.platform.intelligence;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class IntelligenceClientTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<String> body = new AtomicReference<>();
    private HttpServer server;
    private IntelligenceClient client;
    private byte[] fixture;

    @BeforeEach
    void startTechnicalFixtureServer() throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/fixtures/intelligence-health-v1.json")) {
            fixture = Objects.requireNonNull(input).readAllBytes();
        }
        body.set(new String(fixture, StandardCharsets.UTF_8));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/health/ready", exchange -> {
            var payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) { output.write(payload); }
        });
        server.start();
        client = new IntelligenceClient(mapper, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopFixtureServer() {
        client.close();
        server.stop(0);
    }

    @Test
    void validatesTheSharedFixtureThroughRealHttpAndReturnsTheTypedEnvelope() throws Exception {
        assertThat(client.check()).contains(mapper.readValue(fixture, IntelligenceClient.TechnicalHealth.class));
    }

    @Test
    void invalidOrOversizedResponsesAndAnUnavailableServiceNeverCountAsHealthy() throws Exception {
        for (String invalid : new String[]{"{}", "{\"status\":\"UP\"}",
                new String(fixture, StandardCharsets.UTF_8).replace("\"1\"", "\"2\""), "x".repeat(17_000)}) {
            body.set(invalid);
            assertThat(client.check()).isEmpty();
        }
        assertTimeout(Duration.ofSeconds(4), () -> {
            try (var unavailable = new IntelligenceClient(mapper, "http://127.0.0.1:1")) {
                assertThat(unavailable.check()).isEmpty();
            }
        });
    }

    @Test
    void credentialsInServiceUrlsFailConfigurationWithoutReflectingTheSecret() {
        assertThatThrownBy(() -> new IntelligenceClient(mapper, "http://user:private-secret@127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private-secret");
    }
}
