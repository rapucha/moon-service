package dev.moonservice.backend.location;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.moonservice.backend.observability.CacheMetricsSnapshot;
import dev.moonservice.backend.observability.CacheMetricsSource;

import java.time.Duration;
import java.util.Objects;

/**
 * Process-local cache for provider-backed location resolution. Query lookups
 * are keyed by normalized text, selected-location lookups by backend
 * location ID, and Caffeine's per-key load path coalesces concurrent identical
 * misses into one upstream resolver call.
 */
public final class CachingLocationResolver implements LocationResolver, CacheMetricsSource {
    private static final long DEFAULT_MAXIMUM_SIZE = 2_000;
    private static final Duration DEFAULT_RESOLVED_TTL = Duration.ofHours(24);
    private static final Duration DEFAULT_AMBIGUOUS_TTL = Duration.ofHours(24);
    private static final Duration DEFAULT_NOT_FOUND_TTL = Duration.ofMinutes(10);
    private static final Duration DEFAULT_TEMPORARILY_UNAVAILABLE_TTL = Duration.ofSeconds(30);

    private final LocationResolver delegate;
    private final Cache<CacheKey, LocationResolution> cache;

    public static CachingLocationResolver withDefaults(LocationResolver delegate) {
        return new CachingLocationResolver(
                delegate,
                DEFAULT_MAXIMUM_SIZE,
                DEFAULT_RESOLVED_TTL,
                DEFAULT_AMBIGUOUS_TTL,
                DEFAULT_NOT_FOUND_TTL,
                DEFAULT_TEMPORARILY_UNAVAILABLE_TTL,
                Ticker.systemTicker());
    }

    CachingLocationResolver(
            LocationResolver delegate,
            long maximumSize,
            Duration resolvedTtl,
            Duration ambiguousTtl,
            Duration notFoundTtl,
            Duration temporarilyUnavailableTtl,
            Ticker ticker
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cache = Caffeine.newBuilder()
                .maximumSize(requirePositive(maximumSize, "maximumSize"))
                .recordStats()
                .expireAfter(new StatusExpiry(
                        requirePositive(resolvedTtl, "resolvedTtl"),
                        requirePositive(ambiguousTtl, "ambiguousTtl"),
                        requirePositive(notFoundTtl, "notFoundTtl"),
                        requirePositive(temporarilyUnavailableTtl, "temporarilyUnavailableTtl")))
                .ticker(Objects.requireNonNull(ticker, "ticker"))
                .build();
    }

    @Override
    public LocationResolution resolve(LocationQuery query) {
        Objects.requireNonNull(query, "query");
        CacheKey key = CacheKey.query(normalizedQuery(query.text()));
        return cache.get(key, ignored -> delegate.resolve(new LocationQuery(key.value())));
    }

    @Override
    public LocationResolution resolveLocationId(String locationId) {
        if (locationId == null) {
            return delegate.resolveLocationId(null);
        }
        CacheKey key = CacheKey.locationId(locationId.strip());
        return cache.get(key, ignored -> delegate.resolveLocationId(key.value()));
    }

    @Override
    public String cacheName() {
        return "geocoding";
    }

    @Override
    public CacheMetricsSnapshot cacheMetrics() {
        return CacheMetricsSnapshot.from(cache);
    }

    private static String normalizedQuery(String query) {
        return query.strip().replaceAll("\\s+", " ");
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private record CacheKey(CacheKeyKind kind, String value) {
        private CacheKey {
            value = Objects.requireNonNull(value, "value");
        }

        static CacheKey query(String value) {
            return new CacheKey(CacheKeyKind.QUERY, value);
        }

        static CacheKey locationId(String value) {
            return new CacheKey(CacheKeyKind.LOCATION_ID, value);
        }
    }

    private enum CacheKeyKind {
        QUERY,
        LOCATION_ID
    }

    private record StatusExpiry(
            Duration resolvedTtl,
            Duration ambiguousTtl,
            Duration notFoundTtl,
            Duration temporarilyUnavailableTtl
    ) implements Expiry<CacheKey, LocationResolution> {
        @Override
        public long expireAfterCreate(CacheKey key, LocationResolution value, long currentTime) {
            return ttl(value).toNanos();
        }

        @Override
        public long expireAfterUpdate(
                CacheKey key,
                LocationResolution value,
                long currentTime,
                long currentDuration
        ) {
            return expireAfterCreate(key, value, currentTime);
        }

        @Override
        public long expireAfterRead(
                CacheKey key,
                LocationResolution value,
                long currentTime,
                long currentDuration
        ) {
            return currentDuration;
        }

        private Duration ttl(LocationResolution value) {
            return switch (value.status()) {
                case RESOLVED -> resolvedTtl;
                case AMBIGUOUS -> ambiguousTtl;
                case NOT_FOUND -> notFoundTtl;
                case TEMPORARILY_UNAVAILABLE -> temporarilyUnavailableTtl;
            };
        }
    }
}
