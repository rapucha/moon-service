package dev.moonservice.backend.web;

import dev.moonservice.backend.observability.CacheMetricsSnapshot;
import dev.moonservice.backend.observability.CacheMetricsSource;
import dev.moonservice.backend.observability.OpenMeteoObservability;
import dev.moonservice.backend.observability.ProviderQuotaMonitor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
class AdminStatusController {
    private final OpenMeteoObservability openMeteoObservability;
    private final ProviderQuotaMonitor providerQuotaMonitor;
    private final List<CacheMetricsSource> cacheMetricsSources;

    AdminStatusController(
            OpenMeteoObservability openMeteoObservability,
            ProviderQuotaMonitor providerQuotaMonitor,
            List<CacheMetricsSource> cacheMetricsSources
    ) {
        this.openMeteoObservability = openMeteoObservability;
        this.providerQuotaMonitor = providerQuotaMonitor;
        this.cacheMetricsSources = List.copyOf(cacheMetricsSources);
    }

    @GetMapping("/admin/status")
    AdminStatusResponse status() {
        return new AdminStatusResponse(
                new AppStatus("ok"),
                new ProviderStatus(
                        openMeteoObservability.geocodingSnapshot(),
                        openMeteoObservability.weatherSnapshot(),
                        providerQuotaMonitor.snapshots()),
                cacheMetrics());
    }

    private Map<String, CacheMetricsSnapshot> cacheMetrics() {
        Map<String, CacheMetricsSnapshot> snapshots = new LinkedHashMap<>();
        cacheMetricsSources.stream()
                .sorted(Comparator.comparing(CacheMetricsSource::cacheName))
                .forEach(source -> snapshots.put(source.cacheName(), source.cacheMetrics()));
        return snapshots;
    }

    record AdminStatusResponse(
            AppStatus app,
            ProviderStatus providers,
            Map<String, CacheMetricsSnapshot> caches
    ) {
    }

    record AppStatus(String status) {
    }

    record ProviderStatus(
            OpenMeteoObservability.GeocodingSnapshot openMeteoGeocoding,
            OpenMeteoObservability.WeatherSnapshot openMeteoWeather,
            Map<String, ProviderQuotaMonitor.ProviderQuotaSnapshot> operations
    ) {
    }
}
