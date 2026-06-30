package dev.moonservice.backend.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.observability.CacheMetricsSnapshot;
import dev.moonservice.backend.observability.CacheMetricsSource;
import dev.moonservice.backend.observability.OpenMeteoObservability;
import dev.moonservice.backend.observability.quota.ProviderQuotaMonitor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

class AdminStatusControllerTest {
    @Test
    void returnsAggregateProviderAndCacheStatus() {
        ProviderQuotaMonitor quotaMonitor = openMeteoQuotaMonitor();
        OpenMeteoObservability observability = new OpenMeteoObservability(quotaMonitor);
        observability.geocoding().recordProviderCall();
        observability.geocoding().recordLocationOutcome(LocationResolution.Status.RESOLVED, 2_000_000);
        observability.weather().recordProviderCall();
        observability.weather().recordWeatherAvailable(3_000_000);
        CacheMetricsSource cache = new CacheMetricsSource() {
            @Override
            public String cacheName() {
                return "geocoding";
            }

            @Override
            public CacheMetricsSnapshot cacheMetrics() {
                return new CacheMetricsSnapshot(2, 1, 1, 0.5, 1);
            }
        };

        AdminStatusController.AdminStatusResponse response = new AdminStatusController(
                observability,
                quotaMonitor,
                List.of(cache))
                .status();

        assertEquals("ok", response.app().status());
        assertEquals(1, response.providers().openMeteoGeocoding().calls());
        assertEquals(1, response.providers().openMeteoGeocoding().resolved());
        assertEquals(1, response.providers().openMeteoWeather().calls());
        assertEquals(1, response.providers().openMeteoWeather().available());
        assertEquals(1, response.providers()
                .operations()
                .get(OpenMeteoObservability.GEOCODING_OPERATION.id())
                .usage()
                .hourly()
                .used());
        assertEquals(0.5, response.caches().get("geocoding").hitRate(), 0.0001);
    }

    private static ProviderQuotaMonitor openMeteoQuotaMonitor() {
        return new ProviderQuotaMonitor(
                Clock.fixed(Instant.parse("2026-06-29T12:34:56Z"), ZoneOffset.UTC),
                List.of(OpenMeteoObservability.GEOCODING_OPERATION, OpenMeteoObservability.WEATHER_OPERATION));
    }
}
