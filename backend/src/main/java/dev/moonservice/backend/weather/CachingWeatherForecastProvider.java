package dev.moonservice.backend.weather;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.observability.CacheMetricsSnapshot;
import dev.moonservice.backend.observability.CacheMetricsSource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Process-local cache for provider-backed weather forecasts. Keys mirror the
 * upstream request shape: rounded coordinates, elevation, UTC forecast hours,
 * and forecast horizon; Caffeine coalesces concurrent identical misses into one
 * upstream weather call.
 */
public final class CachingWeatherForecastProvider implements WeatherForecastProvider, CacheMetricsSource {
    private final WeatherForecastProvider delegate;
    private final Cache<ForecastKey, ForecastLookup> cache;

    public static CachingWeatherForecastProvider withSettings(
            WeatherForecastProvider delegate,
            long maximumSize,
            Duration availableTtl,
            Duration unavailableTtl
    ) {
        return new CachingWeatherForecastProvider(
                delegate,
                maximumSize,
                availableTtl,
                unavailableTtl,
                Ticker.systemTicker());
    }

    CachingWeatherForecastProvider(
            WeatherForecastProvider delegate,
            long maximumSize,
            Duration availableTtl,
            Duration unavailableTtl,
            Ticker ticker
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cache = Caffeine.newBuilder()
                .maximumSize(requirePositive(maximumSize, "maximumSize"))
                .recordStats()
                .expireAfter(new ForecastLookupExpiry(
                        requirePositive(availableTtl, "availableTtl"),
                        requirePositive(unavailableTtl, "unavailableTtl")))
                .ticker(Objects.requireNonNull(ticker, "ticker"))
                .build();
    }

    @Override
    public WeatherForecast forecastFor(
            ResolvedLocation location,
            Instant startsAt,
            Instant endsAt,
            int forecastHorizonDays
    ) {
        ForecastKey key = ForecastKey.from(location, startsAt, endsAt, forecastHorizonDays);
        return cache.get(key, ignored -> lookup(location, startsAt, endsAt, forecastHorizonDays))
                .forecastOrThrow();
    }

    private ForecastLookup lookup(
            ResolvedLocation location,
            Instant startsAt,
            Instant endsAt,
            int forecastHorizonDays
    ) {
        try {
            return ForecastLookup.available(delegate.forecastFor(location, startsAt, endsAt, forecastHorizonDays));
        } catch (WeatherForecastUnavailableException ex) {
            return ForecastLookup.unavailable(ex);
        }
    }

    @Override
    public String cacheName() {
        return "weather";
    }

    @Override
    public CacheMetricsSnapshot cacheMetrics() {
        return CacheMetricsSnapshot.from(cache);
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

    private record ForecastKey(
            long latitudeTenThousandths,
            long longitudeTenThousandths,
            int elevationMeters,
            Instant startHour,
            Instant endHour,
            int forecastHorizonDays
    ) {
        static ForecastKey from(
                ResolvedLocation location,
                Instant startsAt,
                Instant endsAt,
                int forecastHorizonDays
        ) {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(startsAt, "startsAt");
            Objects.requireNonNull(endsAt, "endsAt");
            return new ForecastKey(
                    coordinateKey(location.latitude()),
                    coordinateKey(location.longitude()),
                    location.elevationMeters(),
                    startsAt.truncatedTo(ChronoUnit.HOURS),
                    endsAt.minusSeconds(1).truncatedTo(ChronoUnit.HOURS),
                    forecastHorizonDays);
        }

        private static long coordinateKey(double value) {
            return Math.round(value * 10_000.0);
        }
    }

    private record ForecastLookup(WeatherForecast forecast, String unavailableMessage) {
        static ForecastLookup available(WeatherForecast forecast) {
            return new ForecastLookup(Objects.requireNonNull(forecast, "forecast"), null);
        }

        static ForecastLookup unavailable(WeatherForecastUnavailableException failure) {
            String message = failure.getMessage();
            if (message == null || message.isBlank()) {
                message = "Weather lookup is temporarily unavailable.";
            }
            return new ForecastLookup(null, message);
        }

        WeatherForecast forecastOrThrow() {
            if (unavailableMessage != null) {
                throw new WeatherForecastUnavailableException(unavailableMessage);
            }
            return forecast;
        }

        boolean isAvailable() {
            return unavailableMessage == null;
        }
    }

    private record ForecastLookupExpiry(
            Duration availableTtl,
            Duration unavailableTtl
    ) implements Expiry<ForecastKey, ForecastLookup> {
        @Override
        public long expireAfterCreate(ForecastKey key, ForecastLookup value, long currentTime) {
            return ttl(value).toNanos();
        }

        @Override
        public long expireAfterUpdate(
                ForecastKey key,
                ForecastLookup value,
                long currentTime,
                long currentDuration
        ) {
            return expireAfterCreate(key, value, currentTime);
        }

        @Override
        public long expireAfterRead(
                ForecastKey key,
                ForecastLookup value,
                long currentTime,
                long currentDuration
        ) {
            return currentDuration;
        }

        private Duration ttl(ForecastLookup value) {
            return value.isAvailable() ? availableTtl : unavailableTtl;
        }
    }
}
