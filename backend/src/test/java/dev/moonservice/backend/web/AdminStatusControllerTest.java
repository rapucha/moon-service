package dev.moonservice.backend.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.observability.CacheMetricsSnapshot;
import dev.moonservice.backend.observability.CacheMetricsSource;
import dev.moonservice.backend.observability.OpenMeteoObservability;

import java.util.List;

import org.junit.jupiter.api.Test;

class AdminStatusControllerTest {
    @Test
    void returnsAggregateProviderAndCacheStatus() {
        OpenMeteoObservability observability = new OpenMeteoObservability();
        observability.geocoding().recordLocationOutcome(LocationResolution.Status.RESOLVED, 2_000_000);
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
                List.of(cache))
                .status();

        assertEquals("ok", response.app().status());
        assertEquals(1, response.providers().openMeteoGeocoding().calls());
        assertEquals(1, response.providers().openMeteoGeocoding().resolved());
        assertEquals(1, response.providers().openMeteoWeather().calls());
        assertEquals(1, response.providers().openMeteoWeather().available());
        assertEquals(0.5, response.caches().get("geocoding").hitRate(), 0.0001);
    }
}
