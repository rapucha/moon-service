package dev.moonservice.backend.weather.openmeteo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.moonservice.backend.location.LocationProvider;
import dev.moonservice.backend.location.ProviderLocationId;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.openmeteo.OpenMeteoTransport;
import dev.moonservice.backend.openmeteo.OpenMeteoTransportException;
import dev.moonservice.backend.openmeteo.RetryingOpenMeteoTransport;
import dev.moonservice.backend.weather.HourlyWeather;
import dev.moonservice.backend.weather.WeatherForecast;
import dev.moonservice.backend.weather.WeatherForecastUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@ExtendWith(OutputCaptureExtension.class)
class OpenMeteoWeatherClientTest {
    @Test
    void buildsOpenMeteoWeatherRequest() {
        OpenMeteoWeatherClient client = client(requestUri -> "{}");

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
    void buildsConfiguredOpenMeteoWeatherRequest() {
        OpenMeteoWeatherClient client = new OpenMeteoWeatherClient(
                URI.create("https://example.test/forecast"),
                requestUri -> "{}");

        URI requestUri = client.requestUri(
                amsterdam(),
                Instant.parse("2026-06-29T00:12:00Z"),
                Instant.parse("2026-06-30T00:00:00Z"));

        assertEquals("https", requestUri.getScheme());
        assertEquals("example.test", requestUri.getHost());
        assertEquals("/forecast", requestUri.getPath());
    }

    @Test
    void mapsProviderHourlyForecastToNormalizedWeather(CapturedOutput output) throws Exception {
        String responseBody = fixture("amsterdam-hourly.json");
        AtomicReference<URI> capturedRequestUri = new AtomicReference<>();
        OpenMeteoWeatherClient client = client(requestUri -> {
            capturedRequestUri.set(requestUri);
            return responseBody;
        });

        WeatherForecast forecast = client.forecastFor(
                amsterdam(),
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-06-29T02:00:00Z"));

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
        assertThat(output).doesNotContain("open_meteo_unexpected_weather_codes");
    }

    @Test
    void logsUnexpectedWeatherCodesOnceWithoutProviderContext(CapturedOutput output) {
        OpenMeteoWeatherClient client = client(ignoredRequestUri -> """
                {
                  "private_marker": "private-response-marker",
                  "hourly": {
                    "time": [1782691200, 1782694800, 1782698400, 1782702000],
                    "cloud_cover": [1, 2, 3, 4],
                    "cloud_cover_low": [1, 2, 3, 4],
                    "cloud_cover_mid": [1, 2, 3, 4],
                    "cloud_cover_high": [1, 2, 3, 4],
                    "precipitation_probability": [0, 0, 0, 0],
                    "precipitation": [0, 0, 0, 0],
                    "weather_code": [100, 4, 100, 47],
                    "visibility": [24000, 24000, 24000, 24000]
                  }
                }
                """);

        client.forecastFor(
                amsterdam(),
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-06-30T00:00:00Z"));

        assertThat(output)
                .contains("WARN")
                .containsOnlyOnce("open_meteo_unexpected_weather_codes")
                .contains("codes=[4, 47, 100]")
                .doesNotContain("amsterdam-nl")
                .doesNotContain("Amsterdam, North Holland, Netherlands")
                .doesNotContain("52.37403")
                .doesNotContain("4.88969")
                .doesNotContain("api.open-meteo.com")
                .doesNotContain("1782691200")
                .doesNotContain("private-response-marker");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-hourly.json", "malformed-hourly-length.json"})
    void mapsMalformedProviderShapeToUnavailable(String fixtureName) throws Exception {
        String responseBody = fixture(fixtureName);
        OpenMeteoWeatherClient client = client(requestUri ->
                responseBody);

        assertThrows(
                WeatherForecastUnavailableException.class,
                () -> client.forecastFor(
                        amsterdam(),
                        Instant.parse("2026-06-29T00:00:00Z"),
                        Instant.parse("2026-06-30T00:00:00Z")));
    }

    @Test
    void mapsInvalidHourlyValuesToUnavailableWithoutLoggingPartialResponse(CapturedOutput output) {
        OpenMeteoWeatherClient client = client(ignoredRequestUri -> """
                {
                  "hourly": {
                    "time": [1782691200, 1782694800],
                    "cloud_cover": [1, "not-a-number"],
                    "cloud_cover_low": [3, 3],
                    "cloud_cover_mid": [4, 4],
                    "cloud_cover_high": [5, 5],
                    "precipitation_probability": [6, 6],
                    "precipitation": [0, 0],
                    "weather_code": [4, 2],
                    "visibility": [24000, 24000]
                  }
                }
                """);

        assertThrows(
                WeatherForecastUnavailableException.class,
                () -> client.forecastFor(
                        amsterdam(),
                        Instant.parse("2026-06-29T00:00:00Z"),
                        Instant.parse("2026-06-30T00:00:00Z")));
        assertThat(output).doesNotContain("open_meteo_unexpected_weather_codes");
    }

    @Test
    void mapsInvalidJsonToUnavailable() {
        OpenMeteoWeatherClient client = client(requestUri -> "{");

        assertThrows(
                WeatherForecastUnavailableException.class,
                () -> client.forecastFor(
                        amsterdam(),
                        Instant.parse("2026-06-29T00:00:00Z"),
                        Instant.parse("2026-06-30T00:00:00Z")));
    }

    @Test
    void mapsTransportFailureToUnavailable() {
        OpenMeteoWeatherClient client = client(requestUri ->
                throwFailure(OpenMeteoTransportException.ioFailure(null)));

        assertThrows(
                WeatherForecastUnavailableException.class,
                () -> client.forecastFor(
                        amsterdam(),
                        Instant.parse("2026-06-29T00:00:00Z"),
                        Instant.parse("2026-06-30T00:00:00Z")));
    }

    @Test
    void retriesTransientHttpFailureOnce() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(
                ResponseStep.failure(OpenMeteoTransportException.transientHttp(503, Optional.empty())),
                ResponseStep.success(fixture("amsterdam-hourly.json")));
        OpenMeteoWeatherClient client = client(retrying(transport));

        WeatherForecast forecast = client.forecastFor(
                amsterdam(),
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-06-29T02:00:00Z"));

        assertEquals(22, forecast.weatherAt(Instant.parse("2026-06-29T00:30:00Z")).cloudCoverPercent());
        assertEquals(2, transport.calls());
    }

    @Test
    void retriesRateLimitWhenRetryAfterIsShort() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(
                ResponseStep.failure(OpenMeteoTransportException.rateLimited(
                        429,
                        Optional.of(Duration.ZERO))),
                ResponseStep.success(fixture("amsterdam-hourly.json")));
        OpenMeteoWeatherClient client = client(retrying(transport));

        WeatherForecast forecast = client.forecastFor(
                amsterdam(),
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-06-29T02:00:00Z"));

        assertEquals(22, forecast.weatherAt(Instant.parse("2026-06-29T00:30:00Z")).cloudCoverPercent());
        assertEquals(2, transport.calls());
    }

    @Test
    void doesNotRetryRateLimitWhenRetryAfterIsLong() {
        ScriptedTransport transport = new ScriptedTransport(ResponseStep.failure(
                OpenMeteoTransportException.rateLimited(
                        429,
                        Optional.of(Duration.ofSeconds(60)))));
        OpenMeteoWeatherClient client = client(retrying(transport));

        assertThrows(
                WeatherForecastUnavailableException.class,
                () -> client.forecastFor(
                        amsterdam(),
                        Instant.parse("2026-06-29T00:00:00Z"),
                        Instant.parse("2026-06-29T02:00:00Z")));
        assertEquals(1, transport.calls());
    }

    @Test
    void doesNotRetryNonRetryableHttpFailure() {
        ScriptedTransport transport = new ScriptedTransport(ResponseStep.failure(
                OpenMeteoTransportException.nonRetryableHttp(404, Optional.empty())));
        OpenMeteoWeatherClient client = client(retrying(transport));

        assertThrows(
                WeatherForecastUnavailableException.class,
                () -> client.forecastFor(
                        amsterdam(),
                        Instant.parse("2026-06-29T00:00:00Z"),
                        Instant.parse("2026-06-29T02:00:00Z")));
        assertEquals(1, transport.calls());
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

    private static OpenMeteoWeatherClient client(OpenMeteoTransport transport) {
        return new OpenMeteoWeatherClient(
                URI.create("https://api.open-meteo.com/v1/forecast"),
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
