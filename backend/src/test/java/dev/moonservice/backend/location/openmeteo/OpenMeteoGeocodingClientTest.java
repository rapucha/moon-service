package dev.moonservice.backend.location.openmeteo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenMeteoGeocodingClientTest {
    @Test
    void buildsOpenMeteoGeocodingRequest() {
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient((requestUri, timeout) -> "{}");

        URI requestUri = client.requestUri(new LocationQuery("Praha"));

        assertEquals(
                "https://geocoding-api.open-meteo.com/v1/search?name=Praha&count=10&language=en&format=json",
                requestUri.toString());
    }

    @Test
    void sendsEncodedRequestAndMapsSingleProviderResultToResolvedLocation() throws Exception {
        String responseBody = fixture("praha-resolved.json");
        AtomicReference<URI> capturedRequestUri = new AtomicReference<>();
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient((requestUri, timeout) -> {
            capturedRequestUri.set(requestUri);
            return responseBody;
        });

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.RESOLVED, resolution.status());
        assertEquals("openmeteo-3067696", resolution.candidates().getFirst().locationId());
        assertEquals("openmeteo:3067696", resolution.candidates().getFirst().providerLocationId().serialized());
        assertEquals("Praha, Hlavni mesto Praha, Czechia", resolution.candidates().getFirst().displayName());
        assertEquals(50.08804, resolution.candidates().getFirst().latitude());
        assertEquals(14.42076, resolution.candidates().getFirst().longitude());
        assertEquals(202, resolution.candidates().getFirst().elevationMeters());
        assertEquals("Europe/Prague", resolution.candidates().getFirst().zoneId().getId());
        assertEquals("CZ", resolution.candidates().getFirst().countryCode());
        assertEquals(
                "https://geocoding-api.open-meteo.com/v1/search?name=Praha&count=10&language=en&format=json",
                capturedRequestUri.get().toString());
    }

    @Test
    void mapsMultipleProviderResultsToAmbiguousLocation() throws Exception {
        String responseBody = fixture("prague-ambiguous.json");
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient((requestUri, timeout) ->
                responseBody);

        LocationResolution resolution = client.resolve(new LocationQuery("Prague"));

        assertEquals(LocationResolution.Status.AMBIGUOUS, resolution.status());
        assertEquals(3, resolution.candidates().size());
        assertEquals("openmeteo-3067696", resolution.candidates().get(0).locationId());
        assertEquals("openmeteo:3067696", resolution.candidates().get(0).providerLocationId().serialized());
        assertEquals("Prague, Hlavni mesto Praha, Czechia", resolution.candidates().get(0).displayName());
        assertEquals("openmeteo-4548393", resolution.candidates().get(1).locationId());
        assertEquals("openmeteo:4548393", resolution.candidates().get(1).providerLocationId().serialized());
        assertEquals("Prague, Oklahoma, United States", resolution.candidates().get(1).displayName());
        assertEquals(35.48674, resolution.candidates().get(1).latitude());
        assertEquals(-96.68502, resolution.candidates().get(1).longitude());
        assertEquals(309, resolution.candidates().get(1).elevationMeters());
        assertEquals("America/Chicago", resolution.candidates().get(1).zoneId().getId());
    }

    @Test
    void mapsValidEmptyProviderResultsToLocationNotFound() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(ResponseStep.success(fixture("tokyo-native-script-miss.json")));
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient(transport);

        LocationResolution resolution = client.resolve(new LocationQuery("東京"));

        assertEquals(LocationResolution.Status.NOT_FOUND, resolution.status());
        assertEquals(1, transport.calls());
    }

    @Test
    void mapsMissingProviderResultsFieldToLocationNotFound() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(ResponseStep.success(fixture("no-results-field.json")));
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient(transport);

        LocationResolution resolution = client.resolve(new LocationQuery("MoonServiceDefinitelyNotAPlace"));

        assertEquals(LocationResolution.Status.NOT_FOUND, resolution.status());
        assertEquals(1, transport.calls());
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty-response.json", "malformed-results.json"})
    void mapsMalformedOrEmptyProviderShapeToTemporarilyUnavailable(String fixtureName) throws Exception {
        String responseBody = fixture(fixtureName);
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient((requestUri, timeout) ->
                responseBody);

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, resolution.status());
    }

    @Test
    void mapsInvalidJsonToTemporarilyUnavailable() {
        ScriptedTransport transport = new ScriptedTransport(ResponseStep.success("{"));
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient(transport);

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, resolution.status());
        assertEquals(1, transport.calls());
    }

    @Test
    void mapsTransportFailureToTemporarilyUnavailable() {
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient((requestUri, timeout) ->
                throwFailure(OpenMeteoGeocodingTransportException.ioFailure(null)));

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, resolution.status());
    }

    @Test
    void restClientTransportReturnsSuccessfulResponseBody() {
        URI requestUri = URI.create("https://geocoding-api.open-meteo.com/v1/search?name=Praha");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(requestUri))
                .andExpect(header(HttpHeaders.USER_AGENT, "moon-service-backend/0.1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        RestClientOpenMeteoGeocodingTransport transport = new RestClientOpenMeteoGeocodingTransport(builder);

        String body = transport.get(requestUri, Duration.ofSeconds(10));

        assertEquals("{}", body);
        server.verify();
    }

    @Test
    void restClientTransportClassifiesRetryAfterFailure() {
        URI requestUri = URI.create("https://geocoding-api.open-meteo.com/v1/search?name=Praha");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(requestUri))
                .andRespond(withServiceUnavailable().header(HttpHeaders.RETRY_AFTER, "1"));
        RestClientOpenMeteoGeocodingTransport transport = new RestClientOpenMeteoGeocodingTransport(builder);

        OpenMeteoGeocodingTransportException failure = assertThrows(
                OpenMeteoGeocodingTransportException.class,
                () -> transport.get(requestUri, Duration.ofSeconds(10)));

        assertEquals(OpenMeteoGeocodingFailureKind.TRANSIENT_HTTP_FAILURE, failure.kind());
        assertEquals(Optional.of(503), failure.statusCode());
        assertEquals(Optional.of(Duration.ofSeconds(1)), failure.retryAfter());
        server.verify();
    }

    @Test
    void retriesTransientHttpFailureOnce() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(
                ResponseStep.failure(OpenMeteoGeocodingTransportException.transientHttp(503, Optional.empty())),
                ResponseStep.success(fixture("praha-resolved.json")));
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient(retrying(transport));

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.RESOLVED, resolution.status());
        assertEquals(2, transport.calls());
    }

    @Test
    void retriesRateLimitWhenRetryAfterIsShort() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(
                ResponseStep.failure(OpenMeteoGeocodingTransportException.rateLimited(
                        429,
                        Optional.of(Duration.ZERO))),
                ResponseStep.success(fixture("praha-resolved.json")));
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient(retrying(transport));

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.RESOLVED, resolution.status());
        assertEquals(2, transport.calls());
    }

    @Test
    void retriesIoFailureOnce() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(
                ResponseStep.failure(OpenMeteoGeocodingTransportException.ioFailure(null)),
                ResponseStep.success(fixture("praha-resolved.json")));
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient(retrying(transport));

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.RESOLVED, resolution.status());
        assertEquals(2, transport.calls());
    }

    @Test
    void doesNotRetryRateLimitWhenRetryAfterIsLong() {
        ScriptedTransport transport = new ScriptedTransport(ResponseStep.failure(
                OpenMeteoGeocodingTransportException.rateLimited(
                        429,
                        Optional.of(Duration.ofSeconds(60)))));
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient(retrying(transport));

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, resolution.status());
        assertEquals(1, transport.calls());
    }

    @Test
    void doesNotRetryNonRetryableHttpFailure() {
        ScriptedTransport transport = new ScriptedTransport(ResponseStep.failure(
                OpenMeteoGeocodingTransportException.nonRetryableHttp(404, Optional.empty())));
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient(retrying(transport));

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, resolution.status());
        assertEquals(1, transport.calls());
    }

    private static String fixture(String name) throws IOException {
        String path = "/fixtures/openmeteo/geocoding/" + name;
        try (InputStream inputStream = OpenMeteoGeocodingClientTest.class.getResourceAsStream(path)) {
            assertNotNull(inputStream, "Missing test fixture: " + path);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static OpenMeteoGeocodingTransport retrying(OpenMeteoGeocodingTransport transport) {
        return new RetryingOpenMeteoGeocodingTransport(
                transport,
                1,
                Duration.ofSeconds(1));
    }

    private static String throwFailure(OpenMeteoGeocodingTransportException failure) {
        throw failure;
    }

    private record ResponseStep(String body, OpenMeteoGeocodingTransportException failure) {
        static ResponseStep success(String body) {
            return new ResponseStep(body, null);
        }

        static ResponseStep failure(OpenMeteoGeocodingTransportException failure) {
            return new ResponseStep(null, failure);
        }
    }

    private static final class ScriptedTransport implements OpenMeteoGeocodingTransport {
        private final List<ResponseStep> steps;
        private int calls;

        private ScriptedTransport(ResponseStep... steps) {
            this.steps = Arrays.asList(steps);
        }

        @Override
        public String get(URI requestUri, Duration timeout) throws OpenMeteoGeocodingTransportException {
            if (calls >= steps.size()) {
                throw new AssertionError("Unexpected Open-Meteo transport call.");
            }
            ResponseStep step = steps.get(calls);
            calls++;
            if (step.failure() != null) {
                throw step.failure();
            }
            return step.body();
        }

        int calls() {
            return calls;
        }
    }
}
