package dev.moonservice.backend.observability;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

public record CacheMetricsSnapshot(
        long requestCount,
        long hitCount,
        long missCount,
        double hitRate,
        long estimatedSize
) {
    public static CacheMetricsSnapshot from(Cache<?, ?> cache) {
        CacheStats stats = cache.stats();
        long requestCount = stats.requestCount();
        return new CacheMetricsSnapshot(
                requestCount,
                stats.hitCount(),
                stats.missCount(),
                requestCount == 0 ? 0.0 : stats.hitRate(),
                cache.estimatedSize());
    }
}
