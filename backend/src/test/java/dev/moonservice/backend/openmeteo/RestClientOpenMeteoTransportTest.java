package dev.moonservice.backend.openmeteo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

class RestClientOpenMeteoTransportTest {
    @Test
    void returnsSuccessfulResponseBody() throws Exception {
        try (TestHttpServer server = TestHttpServer.responding(200, Map.of(), "{}")) {
            URI requestUri = server.uri("/v1/forecast?latitude=52.3740");

            String body = transport().get(requestUri);

            assertEquals("{}", body);
            assertEquals("moon-service-backend/0.1", server.recordedUserAgent());
        }
    }

    @Test
    void classifiesTransientRetryAfterFailure() throws Exception {
        try (TestHttpServer server = TestHttpServer.responding(
                503,
                Map.of(HttpHeaders.RETRY_AFTER, "1"),
                "")) {
            URI requestUri = server.uri("/v1/search?name=Praha");

            OpenMeteoTransportException failure = assertThrows(
                    OpenMeteoTransportException.class,
                    () -> transport().get(requestUri));

            assertEquals(OpenMeteoFailureKind.TRANSIENT_HTTP_FAILURE, failure.kind());
            assertEquals(Optional.of(503), failure.statusCode());
            assertEquals(Optional.of(Duration.ofSeconds(1)), failure.retryAfter());
        }
    }

    @Test
    void classifiesRateLimitRetryAfterFailure() throws Exception {
        try (TestHttpServer server = TestHttpServer.responding(
                429,
                Map.of(HttpHeaders.RETRY_AFTER, "1"),
                "")) {
            URI requestUri = server.uri("/v1/forecast?latitude=52.3740");

            OpenMeteoTransportException failure = assertThrows(
                    OpenMeteoTransportException.class,
                    () -> transport().get(requestUri));

            assertEquals(OpenMeteoFailureKind.RATE_LIMIT, failure.kind());
            assertEquals(Optional.of(429), failure.statusCode());
            assertEquals(Optional.of(Duration.ofSeconds(1)), failure.retryAfter());
        }
    }

    private static RestClientOpenMeteoTransport transport() {
        return new RestClientOpenMeteoTransport(RestClient.builder(), Duration.ofSeconds(5));
    }

    private static final class TestHttpServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> recordedUserAgent = new AtomicReference<>();

        private TestHttpServer(HttpServer server) {
            this.server = server;
        }

        static TestHttpServer responding(
                int statusCode,
                Map<String, String> responseHeaders,
                String responseBody
        ) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TestHttpServer testServer = new TestHttpServer(server);
            server.createContext("/", exchange -> testServer.respond(exchange, statusCode, responseHeaders, responseBody));
            server.start();
            return testServer;
        }

        URI uri(String pathAndQuery) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + pathAndQuery);
        }

        String recordedUserAgent() {
            return recordedUserAgent.get();
        }

        private void respond(
                HttpExchange exchange,
                int statusCode,
                Map<String, String> responseHeaders,
                String responseBody
        ) throws IOException {
            recordedUserAgent.set(exchange.getRequestHeaders().getFirst(HttpHeaders.USER_AGENT));
            responseHeaders.forEach(exchange.getResponseHeaders()::set);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
