package com.stockrescue.platform.persistence;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.stockrescue.support.InfrastructureTestBase;
import com.stockrescue.StockRescueApplication;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresFoundationTest extends InfrastructureTestBase {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway applicationFlyway;

    @LocalServerPort
    private int port;

    @Test
    void schemaMigrationsPreserveApplicationOwnershipAndRevalidateWithoutDrift() throws Exception {
        var flyway = applicationFlyway;
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().applied()).hasSize(8);
        try (var connection = appConnection(); var statement = connection.createStatement()) {
            var current = statement.executeQuery("SELECT current_schema(), current_user");
            assertThat(current.next()).isTrue();
            assertThat(current.getString(1)).as("pooled JDBC current_schema()").isEqualTo("stockrescue");
            assertThat(current.getString(2)).isEqualTo("stockrescue_app");
            var schema = statement.executeQuery("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'stockrescue'");
            assertThat(schema.next()).isTrue();
            assertThat(schema.getString(1)).isEqualTo("stockrescue_app");
            var history = statement.executeQuery("""
                    SELECT n.nspname, pg_get_userbyid(c.relowner)
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relname = 'flyway_schema_history' AND n.nspname IN ('public', 'stockrescue')
                    """);
            assertThat(history.next()).isTrue();
            assertThat(history.getString(1)).isEqualTo("public");
            assertThat(history.getString(2)).isEqualTo("stockrescue_app");
            assertThat(history.next()).isFalse();
            assertPublicSchemaContainsOnlyFlywayHistory(connection);
            var tables = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'stockrescue'");
            assertThat(tables.next()).isTrue();
            assertThat(tables.getInt(1)).isEqualTo(19);
            var role = statement.executeQuery("SELECT rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname = current_user");
            assertThat(role.next()).isTrue();
            assertThat(role.getBoolean(1) || role.getBoolean(2) || role.getBoolean(3)).isFalse();
            var publicGrants = statement.executeQuery("""
                    SELECT count(*) FROM pg_namespace n, LATERAL aclexplode(n.nspacl) acl
                    WHERE n.nspname = 'public' AND acl.grantee = 0
                    """);
            assertThat(publicGrants.next()).isTrue();
            assertThat(publicGrants.getInt(1)).as("PUBLIC privileges on the metadata schema").isZero();
        }
    }

    @Test
    void appliedMigrationGuardRejectsUnqualifiedApplicationObjectsInPublic() throws Exception {
        try (var isolated = new PostgreSQLContainer(DockerImageName.parse(POSTGRES.getDockerImageName()))
                .withDatabaseName("stockrescue_test")
                .withUsername("test_admin")
                .withPassword("test-admin-password")
                .withInitScript("db/test-role.sql")) {
            isolated.start();
            var invalidPublicMigration = Flyway.configure()
                    .dataSource(isolated.getJdbcUrl(), "stockrescue_app", "test-app-password")
                    .schemas("public")
                    .defaultSchema("public")
                    .locations("classpath:db/invalid-public")
                    .cleanDisabled(true)
                    .load();
            assertThat(invalidPublicMigration.migrate().migrationsExecuted).isEqualTo(1);
            try (var connection = DriverManager.getConnection(
                    isolated.getJdbcUrl(), "stockrescue_app", "test-app-password")) {
                assertThatThrownBy(() -> assertPublicSchemaContainsOnlyFlywayHistory(connection))
                        .isInstanceOf(AssertionError.class)
                        .hasMessageContaining("table public.migration_leaked_table")
                        .hasMessageContaining("view public.migration_leaked_view")
                        .hasMessageContaining("sequence public.migration_leaked_sequence")
                        .hasMessageContaining("materialized view public.migration_leaked_materialized_view")
                        .hasMessageContaining("routine public.migration_leaked_function()")
                        .hasMessageContaining("type public.migration_leaked_state");
            }
        }
    }

    @Test
    void unqualifiedDdlUsesApplicationSchemaAndMissingSchemaNeverFallsBackToPublic() throws Exception {
        try (var connection = appConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("CREATE TABLE schema_probe (id INTEGER)");
                var table = statement.executeQuery("""
                        SELECT schemaname, tableowner FROM pg_tables
                        WHERE tablename = 'schema_probe' AND schemaname IN ('public', 'stockrescue')
                        """);
                assertThat(table.next()).isTrue();
                assertThat(table.getString(1)).isEqualTo("stockrescue");
                assertThat(table.getString(2)).isEqualTo("stockrescue_app");
                assertThat(table.next()).isFalse();
            } finally {
                connection.rollback();
            }
            try {
                statement.execute("ALTER SCHEMA stockrescue RENAME TO hidden_application_schema");
                assertThatThrownBy(() -> statement.execute("CREATE TABLE schema_probe (id INTEGER)"))
                        .isInstanceOfSatisfying(SQLException.class,
                                failure -> assertThat(failure.getSQLState()).isEqualTo("3F000"));
            } finally {
                connection.rollback();
            }
            assertThat(connection.getSchema()).isEqualTo("stockrescue");
        }
    }

    @Test
    void previouslyInitializedDatabaseUpgradesAndRestartsWithoutRewritingHistory() throws Exception {
        try (var previous = new PostgreSQLContainer(DockerImageName.parse(POSTGRES.getDockerImageName()))
                .withDatabaseName(POSTGRES.getDatabaseName())
                .withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword())
                .withInitScript("db/test-role.sql")) {
            previous.start();
            // Reproduce the published SR-001 setup: unchanged V001 and the driver's default search path.
            var legacy = Flyway.configure().dataSource(previous.getJdbcUrl(), "stockrescue_app", "test-app-password")
                    .locations("classpath:db/migration").target("1").cleanDisabled(true).load();
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(1);
            String previousHistory;
            try (var connection = DriverManager.getConnection(previous.getJdbcUrl(), "stockrescue_app", "test-app-password")) {
                assertThat(connection.getSchema()).as("published SR-001 connection schema").isEqualTo("public");
                previousHistory = migrationHistory(connection);
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE stockrescue.migration_upgrade_probe (value TEXT NOT NULL)");
                    statement.execute("INSERT INTO stockrescue.migration_upgrade_probe VALUES ('persisted before identity migrations')");
                }
            }

            for (int startup = 0; startup < 2; startup++) {
                try (var upgraded = new SpringApplicationBuilder(StockRescueApplication.class)
                        .web(WebApplicationType.NONE).run(
                                "--spring.datasource.url=" + previous.getJdbcUrl(),
                                "--spring.datasource.username=stockrescue_app",
                                "--spring.datasource.password=test-app-password",
                                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                                "--stockrescue.intelligence.base-url=http://127.0.0.1:1")) {
                    var flyway = upgraded.getBean(Flyway.class);
                    assertThat(flyway.getConfiguration().getDefaultSchema()).isEqualTo("public");
                    flyway.validate();
                    assertThat(flyway.info().applied()).hasSize(8);
                    assertThat(flyway.migrate().migrationsExecuted).isZero();
                    try (var connection = upgraded.getBean(DataSource.class).getConnection()) {
                        assertThat(connection.getSchema()).isEqualTo("stockrescue");
                        assertThat(migrationHistory(connection)).isEqualTo(previousHistory);
                        try (var statement = connection.createStatement();
                             var data = statement.executeQuery("SELECT value FROM stockrescue.migration_upgrade_probe")) {
                            assertThat(data.next()).isTrue();
                            assertThat(data.getString(1)).isEqualTo("persisted before identity migrations");
                            assertThat(data.next()).isFalse();
                        }
                    }
                }
            }
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

    private void assertPublicSchemaContainsOnlyFlywayHistory(Connection connection) throws SQLException {
        assertThat(publicSchemaObjects(connection))
                .as("unexpected application objects in public")
                .isEmpty();
    }

    private List<String> publicSchemaObjects(Connection connection) throws SQLException {
        var objects = new ArrayList<String>();
        var query = """
                SELECT format(
                    CASE c.relkind
                        WHEN 'r' THEN 'table public.%I'
                        WHEN 'p' THEN 'partitioned table public.%I'
                        WHEN 'v' THEN 'view public.%I'
                        WHEN 'm' THEN 'materialized view public.%I'
                        WHEN 'S' THEN 'sequence public.%I'
                        WHEN 'f' THEN 'foreign table public.%I'
                    END,
                    c.relname
                )
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
                  AND NOT (c.relkind = 'r' AND c.relname = 'flyway_schema_history')
                UNION ALL
                SELECT format('routine public.%I(%s)', p.proname, pg_get_function_identity_arguments(p.oid))
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public'
                UNION ALL
                SELECT format('type public.%I', t.typname)
                FROM pg_type t
                JOIN pg_namespace n ON n.oid = t.typnamespace
                WHERE n.nspname = 'public'
                  AND t.oid NOT IN (
                      SELECT c.reltype
                      FROM pg_class c
                      WHERE c.relnamespace = n.oid
                        AND c.relname = 'flyway_schema_history'
                      UNION
                      SELECT row_type.typarray
                      FROM pg_type row_type
                      WHERE row_type.oid = (
                          SELECT c.reltype
                          FROM pg_class c
                          WHERE c.relnamespace = n.oid
                            AND c.relname = 'flyway_schema_history'
                      )
                  )
                ORDER BY 1
                """;
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
            while (result.next()) {
                objects.add(result.getString(1));
            }
        }
        return objects;
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
        return dataSource.getConnection();
    }

    private String migrationHistory(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT row_to_json(history)::text FROM public.flyway_schema_history history WHERE installed_rank = 1")) {
            assertThat(result.next()).isTrue();
            var snapshot = result.getString(1);
            assertThat(result.next()).isFalse();
            return snapshot;
        }
    }

    private int status(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
