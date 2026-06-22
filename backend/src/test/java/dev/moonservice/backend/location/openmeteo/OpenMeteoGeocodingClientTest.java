package dev.moonservice.backend.location.openmeteo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        AtomicReference<URI> capturedRequestUri = new AtomicReference<>();
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient((requestUri, timeout) -> {
            capturedRequestUri.set(requestUri);
            return fixture("praha-resolved.json");
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
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient((requestUri, timeout) ->
                fixture("prague-ambiguous.json"));

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
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient((requestUri, timeout) ->
                fixture("tokyo-native-script-miss.json"));

        LocationResolution resolution = client.resolve(new LocationQuery("東京"));

        assertEquals(LocationResolution.Status.NOT_FOUND, resolution.status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty-response.json", "malformed-results.json"})
    void mapsMalformedOrEmptyProviderShapeToTemporarilyUnavailable(String fixtureName) throws Exception {
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient((requestUri, timeout) ->
                fixture(fixtureName));

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, resolution.status());
    }

    @Test
    void mapsInvalidJsonToTemporarilyUnavailable() {
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient((requestUri, timeout) -> "{");

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, resolution.status());
    }

    @Test
    void mapsTransportFailureToTemporarilyUnavailable() {
        OpenMeteoGeocodingClient client = new OpenMeteoGeocodingClient((requestUri, timeout) -> {
            throw new IOException("provider unavailable");
        });

        LocationResolution resolution = client.resolve(new LocationQuery("Praha"));

        assertEquals(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, resolution.status());
    }

    private static String fixture(String name) throws IOException {
        String path = "/fixtures/openmeteo/geocoding/" + name;
        try (InputStream inputStream = OpenMeteoGeocodingClientTest.class.getResourceAsStream(path)) {
            assertNotNull(inputStream, "Missing test fixture: " + path);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
