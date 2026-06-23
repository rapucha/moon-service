package dev.moonservice.backend.weather.openmeteo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

import dev.moonservice.backend.location.LocationProvider;
import dev.moonservice.backend.location.ProviderLocationId;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.weather.HourlyWeather;
import dev.moonservice.backend.weather.WeatherForecast;
import dev.moonservice.backend.weather.WeatherForecastUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

class OpenMeteoWeatherClientTest {
    @Test
    void buildsOpenMeteoWeatherRequest() {
        OpenMeteoWeatherClient client = new OpenMeteoWeatherClient((requestUri, timeout) -> "{}");

        URI requestUri = client.requestUri(
                amsterdam(),
                Instant.parse("2026-06-29T00:12:00Z"),
                Instant.parse("2026-06-30T00:00:00Z"));

        assertEquals(
                "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=52.3740"
                        + "&longitude=4.8897"
                        + "&elevation=13"
                        + "&hourly=cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high"
                        + ",precipitation_probability,precipitation,weather_code,visibility"
                        + "&timezone=UTC"
                        + "&timeformat=unixtime"
                        + "&start_hour=2026-06-29T00:00"
                        + "&end_hour=2026-06-29T23:00",
                requestUri.toString());
    }

    @Test
    void mapsProviderHourlyForecastToNormalizedWeather() throws Exception {
        String responseBody = fixture("amsterdam-hourly.json");
        AtomicReference<URI> capturedRequestUri = new AtomicReference<>();
        OpenMeteoWeatherClient client = new OpenMeteoWeatherClient((requestUri, timeout) -> {
            capturedRequestUri.set(requestUri);
            return responseBody;
        });

        WeatherForecast forecast = client.forecastFor(
                amsterdam(),
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-06-29T02:00:00Z"),
                7);

        HourlyWeather firstHour = forecast.weatherAt(Instant.parse("2026-06-29T00:30:00Z"));
        HourlyWeather secondHour = forecast.weatherAt(Instant.parse("2026-06-29T01:30:00Z"));
        assertEquals(22, firstHour.cloudCoverPercent());
        assertEquals(3, firstHour.precipitationProbabilityPercent());
        assertEquals(24000, firstHour.visibilityMeters());
        assertEquals(82, secondHour.cloudCoverPercent());
        assertEquals(70, secondHour.lowCloudCoverPercent());
        assertEquals(45, secondHour.midCloudCoverPercent());
        assertEquals(20, secondHour.highCloudCoverPercent());
        assertEquals(35, secondHour.precipitationProbabilityPercent());
        assertEquals(0.8, secondHour.precipitationMm());
        assertEquals(61, secondHour.weatherCode());
        assertEquals(12000, secondHour.visibilityMeters());
        assertEquals(1.0, secondHour.forecastAgeHours());
        assertEquals("https", capturedRequestUri.get().getScheme());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-hourly.json", "malformed-hourly-length.json"})
    void mapsMalformedProviderShapeToUnavailable(String fixtureName) throws Exception {
        String responseBody = fixture(fixtureName);
        OpenMeteoWeatherClient client = new OpenMeteoWeatherClient((requestUri, timeout) ->
                responseBody);

        assertThrows(
                WeatherForecastUnavailableException.class,
                () -> client.forecastFor(
                        amsterdam(),
                        Instant.parse("2026-06-29T00:00:00Z"),
                        Instant.parse("2026-06-30T00:00:00Z"),
                        7));
    }

    @Test
    void mapsInvalidJsonToUnavailable() {
        OpenMeteoWeatherClient client = new OpenMeteoWeatherClient((requestUri, timeout) -> "{");

        assertThrows(
                WeatherForecastUnavailableException.class,
                () -> client.forecastFor(
                        amsterdam(),
                        Instant.parse("2026-06-29T00:00:00Z"),
                        Instant.parse("2026-06-30T00:00:00Z"),
                        7));
    }

    @Test
    void mapsTransportFailureToUnavailable() {
        OpenMeteoWeatherClient client = new OpenMeteoWeatherClient((requestUri, timeout) ->
                throwFailure(OpenMeteoWeatherTransportException.ioFailure(null)));

        assertThrows(
                WeatherForecastUnavailableException.class,
                () -> client.forecastFor(
                        amsterdam(),
                        Instant.parse("2026-06-29T00:00:00Z"),
                        Instant.parse("2026-06-30T00:00:00Z"),
                        7));
    }

    @Test
    void restClientTransportReturnsSuccessfulResponseBody() {
        URI requestUri = URI.create("https://api.open-meteo.com/v1/forecast?latitude=52.3740");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(requestUri))
                .andExpect(header(HttpHeaders.USER_AGENT, "moon-service-backend/0.1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        RestClientOpenMeteoWeatherTransport transport = new RestClientOpenMeteoWeatherTransport(builder);

        String body = transport.get(requestUri, Duration.ofSeconds(10));

        assertEquals("{}", body);
        server.verify();
    }

    @Test
    void restClientTransportClassifiesRateLimitFailure() {
        URI requestUri = URI.create("https://api.open-meteo.com/v1/forecast?latitude=52.3740");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(requestUri))
                .andRespond(withTooManyRequests().header(HttpHeaders.RETRY_AFTER, "1"));
        RestClientOpenMeteoWeatherTransport transport = new RestClientOpenMeteoWeatherTransport(builder);

        OpenMeteoWeatherTransportException failure = assertThrows(
                OpenMeteoWeatherTransportException.class,
                () -> transport.get(requestUri, Duration.ofSeconds(10)));

        assertEquals(OpenMeteoWeatherFailureKind.RATE_LIMIT, failure.kind());
        assertEquals(Optional.of(429), failure.statusCode());
        assertEquals(Optional.of(Duration.ofSeconds(1)), failure.retryAfter());
        server.verify();
    }

    private static ResolvedLocation amsterdam() {
        return new ResolvedLocation(
                "amsterdam-nl",
                new ProviderLocationId(LocationProvider.OPEN_METEO, "2759794"),
                "Amsterdam, North Holland, Netherlands",
                52.37403,
                4.88969,
                13,
                ZoneId.of("Europe/Amsterdam"),
                "NL");
    }

    private static String fixture(String name) throws IOException {
        String path = "/fixtures/openmeteo/weather/" + name;
        try (InputStream inputStream = OpenMeteoWeatherClientTest.class.getResourceAsStream(path)) {
            assertNotNull(inputStream, "Missing test fixture: " + path);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String throwFailure(OpenMeteoWeatherTransportException failure) {
        throw failure;
    }
}
