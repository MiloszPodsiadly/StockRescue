package com.stockrescue.support;

import java.util.List;
import java.util.stream.IntStream;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared assertions for the current versioned migration inventory. */
public final class MigrationAssertions {
    public static final int CURRENT_VERSION = 14;
    private static final MigrationVersion CURRENT_MIGRATION =
            MigrationVersion.fromVersion(Integer.toString(CURRENT_VERSION));
    private static final List<MigrationVersion> EXPECTED_VERSIONS = IntStream.rangeClosed(1, CURRENT_VERSION)
            .mapToObj(version -> MigrationVersion.fromVersion(Integer.toString(version)))
            .toList();

    private MigrationAssertions() {}

    public static void assertCurrent(Flyway flyway) {
        var info = flyway.info();
        assertThat(info.all())
                .filteredOn(migration -> migration.getVersion() != null)
                .extracting(MigrationInfo::getVersion)
                .containsExactlyElementsOf(EXPECTED_VERSIONS);
        assertThat(info.current()).isNotNull();
        assertThat(info.current().getVersion())
                .isEqualTo(CURRENT_MIGRATION);
        assertThat(info.pending()).isEmpty();
        assertThat(info.all())
                .noneSatisfy(migration ->
                        assertThat(migration.getState().name()).startsWith("FAILED"));
        assertThat(info.applied())
                .hasSize(CURRENT_VERSION)
                .allSatisfy(migration ->
                        assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS));
    }

    public static int migrationsAfter(int baselineVersion) {
        if (baselineVersion < 0 || baselineVersion > CURRENT_VERSION) {
            throw new IllegalArgumentException("Invalid migration baseline");
        }
        return CURRENT_VERSION - baselineVersion;
    }
}
