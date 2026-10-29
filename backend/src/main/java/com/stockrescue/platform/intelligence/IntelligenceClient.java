package com.stockrescue.platform.intelligence;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class IntelligenceClient implements AutoCloseable {

    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Definition contract;
    private final HttpClient http;

    public IntelligenceClient(ObjectMapper mapper,
                              @Value("${stockrescue.intelligence.base-url}") String baseUrl) throws IOException {
        this.mapper = mapper;
        this.endpoint = validatedEndpoint(baseUrl);
        try (var input = new ClassPathResource("contracts/intelligence-health-v1.schema.json").getInputStream()) {
            this.contract = mapper.readValue(input, Definition.class);
        }
        if (contract.additionalProperties() || !contract.required().equals(contract.properties().keySet())) {
            throw new IllegalStateException("The technical health contract must require exactly its declared fields");
        }
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Optional<TechnicalHealth> check() {
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2))
                    .header("Accept", "application/json").GET().build();
            var response = http.send(request,
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 16_384));
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            Map<String, Object> body = mapper.readValue(response.body(), new TypeReference<>() { });
            if (body == null || !body.keySet().equals(contract.required())) {
                return Optional.empty();
            }
            for (var field : contract.properties().entrySet()) {
                if (!"string".equals(field.getValue().type())
                        || !field.getValue().expected().equals(body.get(field.getKey()))) {
                    return Optional.empty();
                }
            }
            return Optional.of(mapper.convertValue(body, TechnicalHealth.class));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static URI validatedEndpoint(String input) {
        try {
            var base = URI.create(input);
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return base.resolve("/health/ready");
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid private intelligence base URL; credentials are not permitted");
        }
    }

    @Override
    @PreDestroy
    public void close() {
        http.close();
    }

    public record TechnicalHealth(String contractVersion, String service, String status) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Definition(Map<String, Field> properties, Set<String> required, boolean additionalProperties) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Field(@JsonProperty("const") String expected, String type) { }
}
