package com.stockrescue.identity.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecureSessionCookieTest extends InfrastructureTestBase {
    @LocalServerPort private int port;

    @Test
    void defaultCookieRequiresHttpsEvenWhenUntrustedHeadersClaimHttp() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf"))
                .header("X-Forwarded-Proto", "http").header("Forwarded", "proto=http")
                .header("Origin", "https://attacker.example").GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Set-Cookie").orElseThrow())
                .contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Domain=");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }
}
