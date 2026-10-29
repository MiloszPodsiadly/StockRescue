import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

class Healthcheck {
    public static void main(String[] args) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort()
                    + "/api/health/readiness"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                System.exit(1);
            }
        }
    }

    private static int serverPort() {
        var configuredPort = System.getenv("SERVER_PORT");
        if (configuredPort == null) {
            return 8080;
        }
        final int port;
        try {
            port = Integer.parseInt(configuredPort);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("SERVER_PORT must be an integer between 1 and 65535.", exception);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SERVER_PORT must be between 1 and 65535.");
        }
        return port;
    }
}
