package com.stockrescue.platform.config;

import java.util.List;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class RequiredServiceConfiguration {
    private static final List<Setting> REQUIRED = List.of(
            new Setting("spring.datasource.url", "DATABASE_URL"),
            new Setting("spring.datasource.username", "DATABASE_USERNAME"),
            new Setting("spring.datasource.password", "DATABASE_PASSWORD"),
            new Setting("spring.kafka.bootstrap-servers", "KAFKA_BOOTSTRAP_SERVERS"),
            new Setting("stockrescue.intelligence.base-url", "INTELLIGENCE_BASE_URL"));

    @Bean static BeanFactoryPostProcessor requiredServiceSettings(Environment environment) {
        return factory -> validate(environment);
    }

    static void validate(Environment environment) {
        for (var setting : REQUIRED) {
            String value;
            try {
                value = environment.getProperty(setting.property());
            } catch (IllegalArgumentException unresolvedPlaceholder) {
                value = null;
            }
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Required service setting " + setting.environmentName() + " is missing or blank.");
            }
        }
    }

    private record Setting(String property, String environmentName) { }
}
