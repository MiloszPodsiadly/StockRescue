package com.stockrescue.support;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class InfrastructureTestBase {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-alpine"))
            .withDatabaseName("stockrescue_test")
            .withUsername("test_admin")
            .withPassword("test-admin-password")
            .withInitScript("db/test-role.sql");
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");
    private static final HttpServer INTELLIGENCE_STUB;

    static {
        POSTGRES.start();
        KAFKA.start();
        try {
            byte[] fixture;
            try (var input = InfrastructureTestBase.class.getResourceAsStream("/contracts/fixtures/intelligence-health-v1.json")) {
                fixture = Objects.requireNonNull(input).readAllBytes();
            }
            INTELLIGENCE_STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            INTELLIGENCE_STUB.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            INTELLIGENCE_STUB.createContext("/health/ready", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, fixture.length);
                try (var output = exchange.getResponseBody()) { output.write(fixture); }
            });
            INTELLIGENCE_STUB.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> INTELLIGENCE_STUB.stop(0)));
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "stockrescue_app");
        properties.add("spring.datasource.password", () -> "test-app-password");
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("stockrescue.intelligence.base-url",
                () -> "http://127.0.0.1:" + INTELLIGENCE_STUB.getAddress().getPort());
    }
}
