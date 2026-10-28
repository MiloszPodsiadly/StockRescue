package com.stockrescue.platform.persistence;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;

import com.stockrescue.support.InfrastructureTestBase;
import com.stockrescue.StockRescueApplication;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresFoundationTest extends InfrastructureTestBase {

    @LocalServerPort
    private int port;

    @Test
    void baselineCreatesOnlyTheApplicationSchemaAndRevalidatesWithoutDrift() throws Exception {
        var flyway = isolatedFlyway("public", "classpath:db/migration");
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().applied()).hasSize(1);
        try (var connection = appConnection(); var statement = connection.createStatement()) {
            var schema = statement.executeQuery("SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'stockrescue'");
            assertThat(schema.next()).isTrue();
            var tables = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'stockrescue'");
            assertThat(tables.next()).isTrue();
            assertThat(tables.getInt(1)).isZero();
            var role = statement.executeQuery("SELECT rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname = current_user");
            assertThat(role.next()).isTrue();
            assertThat(role.getBoolean(1) || role.getBoolean(2) || role.getBoolean(3)).isFalse();
        }
    }

    @Test
    void failedMigrationRollsBackAndChecksumMismatchIsRejected() throws Exception {
        var invalid = isolatedFlyway("invalid_case", "classpath:db/invalid");
        assertThatThrownBy(invalid::migrate).isInstanceOf(FlywayException.class);
        try (var connection = appConnection(); var statement = connection.createStatement()) {
            var result = statement.executeQuery("SELECT to_regclass('invalid_case.negative_probe')");
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isNull();
        }

        var changed = isolatedFlyway("checksum_case", "classpath:db/checksum");
        changed.migrate();
        try (var connection = appConnection(); var statement = connection.createStatement()) {
            assertThat(statement.executeUpdate("UPDATE checksum_case.flyway_schema_history SET checksum = 0 WHERE description = 'probe' AND type = 'SQL'"))
                    .isEqualTo(1);
        }
        assertThatThrownBy(() -> isolatedFlyway("checksum_case", "classpath:db/checksum").validate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void rejectedDatabaseConnectionsFailReadinessWhileLivenessSurvivesAndRecoveryWorks() throws Exception {
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            try {
                statement.execute("ALTER ROLE stockrescue_app NOLOGIN");
                statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'stockrescue_app'");
                assertThat(status("/api/health/readiness")).isEqualTo(503);
                assertThat(status("/api/health/liveness")).isEqualTo(200);
            } finally {
                statement.execute("ALTER ROLE stockrescue_app LOGIN");
            }
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> assertThat(status("/api/health/readiness")).isEqualTo(200));
        }
    }

    private Flyway isolatedFlyway(String schema, String location) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "stockrescue_app", "test-app-password")
                .schemas(schema).defaultSchema(schema).locations(location).cleanDisabled(true).load();
    }

    @Test
    void anInvalidMigrationPreventsApplicationStartup() {
        var failure = catchThrowable(() -> new SpringApplicationBuilder(StockRescueApplication.class)
                .web(WebApplicationType.NONE).run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=stockrescue_app",
                        "--spring.datasource.password=test-app-password",
                        "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "--stockrescue.intelligence.base-url=http://127.0.0.1:1",
                        "--spring.flyway.locations=classpath:db/invalid",
                        "--spring.flyway.default-schema=startup_invalid_case",
                        "--spring.flyway.schemas=startup_invalid_case"));
        assertThat(failure).isNotNull();
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        assertThat(failure).isInstanceOf(java.sql.SQLException.class);
        assertThat(failure.getMessage()).contains("deliberately_invalid_migration_function");
    }

    private Connection appConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "stockrescue_app", "test-app-password");
    }

    private int status(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
