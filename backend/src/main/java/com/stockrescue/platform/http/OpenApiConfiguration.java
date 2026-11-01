package com.stockrescue.platform.http;

import java.util.List;
import java.util.Set;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import com.stockrescue.platform.security.SecurityConfiguration;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean OpenApiCustomizer nullableObjectReferences() {
        return api -> {
            for (Schema<?> schema : api.getComponents().getSchemas().values()) {
                if (schema.getProperties() == null) continue;
                schema.getProperties().replaceAll((name, property) -> {
                    Schema<?> typedProperty = property;
                    boolean nullable = Boolean.TRUE.equals(property.getNullable())
                            || property.getTypes() != null && property.getTypes().contains("null");
                    if (nullable && typedProperty.getEnum() != null) allowNullEnum(typedProperty);
                    if (!nullable || property.get$ref() == null) return property;
                    // A nullable sibling type still intersects with an object $ref in
                    // OpenAPI 3.1. Use an explicit union for JSON Schema and typegen.
                    var nullSchema = new Schema<>();
                    nullSchema.setTypes(Set.of("null"));
                    return new ComposedSchema().anyOf(List.of(new Schema<>().$ref(property.get$ref()), nullSchema));
                });
            }
        };
    }
    private static <T> void allowNullEnum(Schema<T> schema) {
        var values = new java.util.ArrayList<>(schema.getEnum());
        if (!values.contains(null)) values.add(null);
        schema.setEnum(values);
    }
    @Bean OpenApiCustomizer csrfContract() {
        return api -> api.getPaths().values().forEach(path -> path.readOperationsMap().forEach((method, operation) -> {
            if (List.of("POST", "PUT", "PATCH", "DELETE").contains(method.name())) {
                operation.addParametersItem(new Parameter().in("header").name(SecurityConfiguration.CSRF_HEADER)
                        .required(true).description("Session-bound CSRF value from the CSRF endpoint")
                        .schema(new StringSchema()));
            }
        }));
    }
    @Bean OpenAPI businessApi() {
        return new OpenAPI().info(new Info().title("StockRescue HTTP API").version("1"))
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes("session", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("SRSESSION")));
    }
}
