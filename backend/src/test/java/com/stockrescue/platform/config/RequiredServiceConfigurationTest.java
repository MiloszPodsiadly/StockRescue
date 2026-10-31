package com.stockrescue.platform.config;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredServiceConfigurationTest {
    private static final Map<String, String> SETTINGS = Map.of(
            "spring.datasource.url", "DATABASE_URL", "spring.datasource.username", "DATABASE_USERNAME",
            "spring.datasource.password", "DATABASE_PASSWORD", "spring.kafka.bootstrap-servers", "KAFKA_BOOTSTRAP_SERVERS",
            "stockrescue.intelligence.base-url", "INTELLIGENCE_BASE_URL");

    @Test
    void completeServiceConfigurationPassesWithoutConnectingToDependencies() {
        assertThatCode(() -> RequiredServiceConfiguration.validate(complete())).doesNotThrowAnyException();
    }

    @Test
    void eachMissingOrBlankSettingNamesOnlyTheRequiredEnvironmentKey() {
        SETTINGS.forEach((property, key) -> {
            for (String missing : new String[]{null, "", "  "}) {
                var environment = complete();
                if (missing == null) environment.getPropertySources().remove("mockProperties");
                // Restore all other values after removing the mock property source.
                if (missing == null) {
                    environment = new MockEnvironment();
                    for (var other : SETTINGS.keySet()) if (!other.equals(property)) environment.setProperty(other, "private-configuration-value");
                } else {
                    environment.setProperty(property, missing);
                }
                var candidate = environment;
                assertThatThrownBy(() -> RequiredServiceConfiguration.validate(candidate))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining(key)
                        .hasMessageNotContaining("private-configuration-value");
            }
        });
    }

    @Test
    void unresolvedPlaceholdersFailBeforeDataSourceInitializationWithoutLeakingValues() {
        var environment = complete().withProperty("spring.datasource.url", "${DATABASE_URL}");
        assertThatThrownBy(() -> RequiredServiceConfiguration.validate(environment))
                .isInstanceOf(IllegalStateException.class).hasMessage("Required service setting DATABASE_URL is missing or blank.");
    }

    private MockEnvironment complete() {
        var environment = new MockEnvironment();
        SETTINGS.keySet().forEach(key -> environment.setProperty(key, "private-configuration-value"));
        return environment;
    }
}
