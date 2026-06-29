package dev.moonservice.backend.location.openmeteo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.openmeteo.OpenMeteoTransport;
import dev.moonservice.backend.openmeteo.OpenMeteoTransportException;
import dev.moonservice.backend.openmeteo.RetryingOpenMeteoTransport;
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

class OpenMeteoGeocodingClientTest {
    @Test
    void buildsOpenMeteoGeocodingRequest() {
        OpenMeteoGeocodingClient client = client(requestUri -> "{}");

        URI requestUri = client.requestUri(new LocationQuery("Praha"));

        assertEquals(
                "https://geocoding-api.open-meteo.com/v1/search?name=Praha&count=10&language=en&format=json",
                requestUri.toString());
    }

    @Test
    void buildsConfiguredOpenMeteoGeocodingRequest() {
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient(
                URI.create("https://example.test/geocoding/search"),
                URI.create("https://example.test/geocoding/get"),
                "cs",
                4,
                requestUri -> "{}");

        URI requestUri = client.requestUri(new LocationQuery("Praha"));
        URI locationIdRequestUri = client.locationIdRequestUri("3067696");

        assertEquals(
                "https://example.test/geocoding/search?name=Praha&count=4&language=cs&format=json",
                requestUri.toString());
        assertEquals(
                "https://example.test/geocoding/get?id=3067696&language=cs&format=json",
                locationIdRequestUri.toString());
    }

    @Test
    void buildsOpenMeteoLocationIdRequestAndMapsSingleProviderResult() throws Exception {
        String responseBody = fixture("prague-get.json");
        AtomicReference<URI> capturedRequestUri = new AtomicReference<>();
        OpenMeteoGeocodingClient client = client(requestUri -> {
            capturedRequestUri.set(requestUri);
            return responseBody;
        });

        LocationResolution resolution = client.resolveLocationId("moon-service-3067696");

        assertEquals(LocationResolution.Status.RESOLVED, resolution.status());
        assertEquals("moon-service-3067696", resolution.candidates().getFirst().locationId());
        assertEquals("openmeteo:3067696", resolution.candidates().getFirst().providerLocationId().serialized());
        assertEquals("Prague, Hlavni mesto Praha, Czechia", resolution.candidates().getFirst().displayName());
        assertEquals(
                "https://geocoding-api.open-meteo.com/v1/get?id=3067696&language=en&format=json",
                capturedRequestUri.get().toString());
    }

    @Test
    void mapsUnsupportedLocationIdToNotFoundWithoutProviderCall() {
        OpenMeteoGeocodingClient client = client(requestUri ->
                fail("Unsupported backend location IDs should not call Open-Meteo."));

        LocationResolution resolution = client.resolveLocationId("springfield-mo-us");

        assertEquals(LocationResolution.Status.NOT_FOUND, resolution.status());
    }

    @Test
    void sendsEncodedRequestAndMapsSingleProviderResultToResolvedLocation() throws Exception {
        String responseBody = fixture("praha-resolved.json");
        AtomicReference<URI> capturedRequestUri = new AtomicReference<>();
        OpenMeteoGeocodingClient client = client(requestUri -> {
            capturedRequestUri.set(requestUri);
            return responseBody;
        });

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.RESOLVED, resolution.status());
        assertEquals("moon-service-3067696", resolution.candidates().getFirst().locationId());
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
        OpenMeteoGeocodingClient client = client(requestUri ->
                responseBody);

        LocationResolution resolution = client.resolve(new LocationQuery("Prague"));

        assertEquals(LocationResolution.Status.AMBIGUOUS, resolution.status());
        assertEquals(3, resolution.candidates().size());
        assertEquals("moon-service-3067696", resolution.candidates().get(0).locationId());
        assertEquals("openmeteo:3067696", resolution.candidates().get(0).providerLocationId().serialized());
        assertEquals("Prague, Hlavni mesto Praha, Czechia", resolution.candidates().get(0).displayName());
        assertEquals("moon-service-4548393", resolution.candidates().get(1).locationId());
        assertEquals("openmeteo:4548393", resolution.candidates().get(1).providerLocationId().serialized());
        assertEquals("Prague, Oklahoma, United States", resolution.candidates().get(1).displayName());
        assertEquals(35.48674, resolution.candidates().get(1).latitude());
        assertEquals(-96.68502, resolution.candidates().get(1).longitude());
        assertEquals(309, resolution.candidates().get(1).elevationMeters());
        assertEquals("America/Chicago", resolution.candidates().get(1).zoneId().getId());
    }

    @Test
    void collapsesSameCityProviderNoiseToCanonicalLocation() throws Exception {
        String responseBody = fixture("prague-czechia-same-city-noise.json");
        OpenMeteoGeocodingClient client = client(requestUri ->
                responseBody);

        LocationResolution resolution = client.resolve(new LocationQuery("prague, czechia"));

        assertEquals(LocationResolution.Status.RESOLVED, resolution.status());
        assertEquals(1, resolution.candidates().size());
        assertEquals("moon-service-3067696", resolution.candidates().getFirst().locationId());
        assertEquals("openmeteo:3067696", resolution.candidates().getFirst().providerLocationId().serialized());
        assertEquals("Prague, Czechia", resolution.candidates().getFirst().displayName());
    }

    @Test
    void keepsDistinctSameNameCitiesAmbiguous() throws Exception {
        String responseBody = fixture("springfield-ambiguous.json");
        OpenMeteoGeocodingClient client = client(requestUri ->
                responseBody);

        LocationResolution resolution = client.resolve(new LocationQuery("Springfield"));

        assertEquals(LocationResolution.Status.AMBIGUOUS, resolution.status());
        assertEquals(3, resolution.candidates().size());
        assertEquals("moon-service-4409896", resolution.candidates().get(0).locationId());
        assertEquals("Springfield, Missouri, United States", resolution.candidates().get(0).displayName());
        assertEquals("moon-service-4250542", resolution.candidates().get(1).locationId());
        assertEquals("Springfield, Illinois, United States", resolution.candidates().get(1).displayName());
        assertEquals("moon-service-4951788", resolution.candidates().get(2).locationId());
        assertEquals("Springfield, Massachusetts, United States", resolution.candidates().get(2).displayName());
    }

    @Test
    void mapsValidEmptyProviderResultsToLocationNotFound() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(ResponseStep.success(fixture("tokyo-native-script-miss.json")));
        OpenMeteoGeocodingClient client = client(transport);

        LocationResolution resolution = client.resolve(new LocationQuery("東京"));

        assertEquals(LocationResolution.Status.NOT_FOUND, resolution.status());
        assertEquals(1, transport.calls());
    }

    @Test
    void mapsMissingProviderResultsFieldToLocationNotFound() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(ResponseStep.success(fixture("no-results-field.json")));
        OpenMeteoGeocodingClient client = client(transport);

        LocationResolution resolution = client.resolve(new LocationQuery("MoonServiceDefinitelyNotAPlace"));

        assertEquals(LocationResolution.Status.NOT_FOUND, resolution.status());
        assertEquals(1, transport.calls());
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty-response.json", "malformed-results.json"})
    void mapsMalformedOrEmptyProviderShapeToTemporarilyUnavailable(String fixtureName) throws Exception {
        String responseBody = fixture(fixtureName);
        OpenMeteoGeocodingClient client = client(requestUri ->
                responseBody);

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, resolution.status());
    }

    @Test
    void mapsInvalidJsonToTemporarilyUnavailable() {
        ScriptedTransport transport = new ScriptedTransport(ResponseStep.success("{"));
        OpenMeteoGeocodingClient client = client(transport);

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, resolution.status());
        assertEquals(1, transport.calls());
    }

    @Test
    void mapsTransportFailureToTemporarilyUnavailable() {
        OpenMeteoGeocodingClient client = client(requestUri ->
                throwFailure(OpenMeteoTransportException.ioFailure(null)));

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, resolution.status());
    }

    @Test
    void retriesTransientHttpFailureOnce() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(
                ResponseStep.failure(OpenMeteoTransportException.transientHttp(503, Optional.empty())),
                ResponseStep.success(fixture("praha-resolved.json")));
        OpenMeteoGeocodingClient client = client(retrying(transport));

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.RESOLVED, resolution.status());
        assertEquals(2, transport.calls());
    }

    @Test
    void retriesRateLimitWhenRetryAfterIsShort() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(
                ResponseStep.failure(OpenMeteoTransportException.rateLimited(
                        429,
                        Optional.of(Duration.ZERO))),
                ResponseStep.success(fixture("praha-resolved.json")));
        OpenMeteoGeocodingClient client = client(retrying(transport));

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.RESOLVED, resolution.status());
        assertEquals(2, transport.calls());
    }

    @Test
    void retriesIoFailureOnce() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(
                ResponseStep.failure(OpenMeteoTransportException.ioFailure(null)),
                ResponseStep.success(fixture("praha-resolved.json")));
        OpenMeteoGeocodingClient client = client(retrying(transport));

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.RESOLVED, resolution.status());
        assertEquals(2, transport.calls());
    }

    @Test
    void doesNotRetryRateLimitWhenRetryAfterIsLong() {
        ScriptedTransport transport = new ScriptedTransport(ResponseStep.failure(
                OpenMeteoTransportException.rateLimited(
                        429,
                        Optional.of(Duration.ofSeconds(60)))));
        OpenMeteoGeocodingClient client = client(retrying(transport));

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, resolution.status());
        assertEquals(1, transport.calls());
    }

    @Test
    void doesNotRetryNonRetryableHttpFailure() {
        ScriptedTransport transport = new ScriptedTransport(ResponseStep.failure(
                OpenMeteoTransportException.nonRetryableHttp(404, Optional.empty())));
        OpenMeteoGeocodingClient client = client(retrying(transport));

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

    private static OpenMeteoGeocodingClient client(OpenMeteoTransport transport) {
        return new OpenMeteoGeocodingClient(
                URI.create("https://geocoding-api.open-meteo.com/v1/search"),
                URI.create("https://geocoding-api.open-meteo.com/v1/get"),
                "en",
                10,
                transport);
    }

    private static OpenMeteoTransport retrying(OpenMeteoTransport transport) {
        return new RetryingOpenMeteoTransport(
                transport,
                1,
                Duration.ofSeconds(1));
    }

    private static String throwFailure(OpenMeteoTransportException failure) {
        throw failure;
    }

    private record ResponseStep(String body, OpenMeteoTransportException failure) {
        static ResponseStep success(String body) {
            return new ResponseStep(body, null);
        }

        static ResponseStep failure(OpenMeteoTransportException failure) {
            return new ResponseStep(null, failure);
        }
    }

    private static final class ScriptedTransport implements OpenMeteoTransport {
        private final List<ResponseStep> steps;
        private int calls;

        private ScriptedTransport(ResponseStep... steps) {
            this.steps = Arrays.asList(steps);
        }

        @Override
        public String get(URI requestUri) throws OpenMeteoTransportException {
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
