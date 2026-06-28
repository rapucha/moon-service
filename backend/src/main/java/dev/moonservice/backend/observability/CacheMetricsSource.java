package dev.moonservice.backend.observability;

public interface CacheMetricsSource {
    String cacheName();

    CacheMetricsSnapshot cacheMetrics();
}
