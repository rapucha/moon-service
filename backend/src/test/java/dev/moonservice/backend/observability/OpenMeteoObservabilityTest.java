package dev.moonservice.backend.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.moonservice.backend.location.LocationProvider;
import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.ProviderLocationId;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.openmeteo.OpenMeteoFailureKind;
import dev.moonservice.backend.openmeteo.OpenMeteoTransport;
import dev.moonservice.backend.openmeteo.OpenMeteoTransportException;
import dev.moonservice.backend.openmeteo.RetryingOpenMeteoTransport;
import dev.moonservice.backend.observability.quota.ProviderQuotaMonitor;
import dev.moonservice.backend.weather.HourlyWeather;
import dev.moonservice.backend.weather.WeatherForecastUnavailableException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class OpenMeteoObservabilityTest {
    @Test
    void recordsLocationOutcomes() {
        OpenMeteoObservability observability = openMeteoObservability();
        ObservedLocationResolver resolver = new ObservedLocationResolver(
                query -> LocationResolution.resolved(location()),
                observability.geocoding());

        resolver.resolve(new LocationQuery("Praha"));

        OpenMeteoObservability.GeocodingSnapshot snapshot = observability.geocodingSnapshot();
        assertEquals(1, snapshot.calls());
        assertEquals(1, snapshot.resolved());
        assertEquals(0, snapshot.ambiguous());
        assertEquals(0, snapshot.notFound());
        assertEquals(0, snapshot.temporarilyUnavailable());
    }

    @Test
    void recordsWeatherOutcomes() {
        OpenMeteoObservability observability = openMeteoObservability();
        ObservedWeatherForecastProvider availableProvider = new ObservedWeatherForecastProvider(
                (location, startsAt, endsAt, forecastHorizonDays) -> instant -> weather(instant),
                observability.weather());
        ObservedWeatherForecastProvider unavailableProvider = new ObservedWeatherForecastProvider(
                (location, startsAt, endsAt, forecastHorizonDays) -> {
                    throw new WeatherForecastUnavailableException("Weather lookup is temporarily unavailable.");
                },
                observability.weather());

        availableProvider.forecastFor(location(), Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), 1);
        assertThrows(
                WeatherForecastUnavailableException.class,
                () -> unavailableProvider.forecastFor(location(), Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), 1));

        OpenMeteoObservability.WeatherSnapshot snapshot = observability.weatherSnapshot();
        assertEquals(2, snapshot.calls());
        assertEquals(1, snapshot.available());
        assertEquals(1, snapshot.temporarilyUnavailable());
    }

    @Test
    void recordsTransportFailureKindsAndRetries() {
        OpenMeteoObservability observability = openMeteoObservability();
        assertThrows(
                OpenMeteoTransportException.class,
                () -> new ObservingOpenMeteoTransport(
                        requestUri -> {
                            throw OpenMeteoTransportException.rateLimited(429, Optional.empty());
                        },
                        observability.geocoding())
                        .get(URI.create("https://example.test/search")));

        assertThrows(
                OpenMeteoTransportException.class,
                () -> new ObservingOpenMeteoTransport(
                        requestUri -> {
                            throw OpenMeteoTransportException.timeout(null);
                        },
                        observability.geocoding())
                        .get(URI.create("https://example.test/search")));

        AtomicInteger calls = new AtomicInteger();
        OpenMeteoTransport retrying = new RetryingOpenMeteoTransport(
                requestUri -> {
                    if (calls.getAndIncrement() == 0) {
                        throw OpenMeteoTransportException.transientHttp(503, Optional.empty());
                    }
                    return "{}";
                },
                1,
                Duration.ofSeconds(1),
                observability.geocoding()::recordRetry);

        assertEquals("{}", retrying.get(URI.create("https://example.test/search")));

        OpenMeteoObservability.GeocodingSnapshot snapshot = observability.geocodingSnapshot();
        assertEquals(1, snapshot.rateLimited());
        assertEquals(1, snapshot.timeouts());
        assertEquals(1, snapshot.retries());
    }

    @Test
    void recordsOutboundTransportCallsForQuotaUsage() {
        ProviderQuotaMonitor quotaMonitor = openMeteoQuotaMonitor();
        OpenMeteoObservability observability = new OpenMeteoObservability(quotaMonitor);
        OpenMeteoTransport transport = new ObservingOpenMeteoTransport(
                requestUri -> "{}",
                observability.weather());

        assertEquals("{}", transport.get(URI.create("https://example.test/forecast")));

        ProviderQuotaMonitor.ProviderQuotaWindowSnapshot hourly = quotaMonitor.snapshots()
                .get(OpenMeteoObservability.WEATHER_OPERATION.id())
                .usage()
                .hourly();
        assertEquals(1, hourly.used());
    }

    private static OpenMeteoObservability openMeteoObservability() {
        return new OpenMeteoObservability(openMeteoQuotaMonitor());
    }

    private static ProviderQuotaMonitor openMeteoQuotaMonitor() {
        return new ProviderQuotaMonitor(
                Clock.fixed(Instant.parse("2026-06-29T12:34:56Z"), ZoneOffset.UTC),
                List.of(OpenMeteoObservability.GEOCODING_OPERATION, OpenMeteoObservability.WEATHER_OPERATION));
    }

    private static ResolvedLocation location() {
        return new ResolvedLocation(
                "moon-service-3067696",
                new ProviderLocationId(LocationProvider.OPEN_METEO, "3067696"),
                "Prague, Czechia",
                50.08804,
                14.42076,
                202,
                ZoneId.of("Europe/Prague"),
                "CZ");
    }

    private static HourlyWeather weather(Instant instant) {
        return new HourlyWeather(
                instant,
                20,
                5,
                10,
                15,
                0,
                0.0,
                24000,
                2,
                1.0);
    }
}
