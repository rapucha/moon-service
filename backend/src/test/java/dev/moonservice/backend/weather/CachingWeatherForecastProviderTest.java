package dev.moonservice.backend.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.moonservice.backend.location.LocationProvider;
import dev.moonservice.backend.location.ProviderLocationId;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.observability.CacheMetricsSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CachingWeatherForecastProviderTest {
    private static final Instant STARTS_AT = Instant.parse("2026-06-29T00:12:00Z");
    private static final Instant SAME_START_HOUR = Instant.parse("2026-06-29T00:45:00Z");
    private static final Instant ENDS_AT = Instant.parse("2026-06-30T00:00:00Z");
    private static final WeatherForecast CLEAR_FORECAST = instant -> weather(instant, 20);
    private static final WeatherForecast CLOUDY_FORECAST = instant -> weather(instant, 80);

    @Test
    void cachesAvailableForecastsByProviderRequestShape() {
        CountingProvider delegate = new CountingProvider(CLEAR_FORECAST);
        CachingWeatherForecastProvider provider = provider(delegate, new FakeTicker());

        WeatherForecast first = provider.forecastFor(amsterdam(), STARTS_AT, ENDS_AT, 7);
        WeatherForecast second = provider.forecastFor(amsterdamWithExtraCoordinatePrecision(), SAME_START_HOUR, ENDS_AT, 7);

        assertEquals(20, first.weatherAt(STARTS_AT).cloudCoverPercent());
        assertEquals(20, second.weatherAt(SAME_START_HOUR).cloudCoverPercent());
        assertEquals(1, delegate.calls());
    }

    @Test
    void exposesCacheMetrics() {
        CountingProvider delegate = new CountingProvider(CLEAR_FORECAST);
        CachingWeatherForecastProvider provider = provider(delegate, new FakeTicker());

        assertEquals("weather", provider.cacheName());
        assertEquals(0, provider.cacheMetrics().requestCount());

        provider.forecastFor(amsterdam(), STARTS_AT, ENDS_AT, 7);
        provider.forecastFor(amsterdam(), STARTS_AT, ENDS_AT, 7);

        CacheMetricsSnapshot metrics = provider.cacheMetrics();
        assertEquals(2, metrics.requestCount());
        assertEquals(1, metrics.hitCount());
        assertEquals(1, metrics.missCount());
        assertEquals(0.5, metrics.hitRate(), 0.0001);
        assertEquals(1, metrics.estimatedSize());
    }

    @Test
    void expiresUnavailableForecastsSoonerThanAvailableForecasts() {
        FakeTicker ticker = new FakeTicker();
        SequenceProvider delegate = new SequenceProvider(List.of(
                new WeatherForecastUnavailableException("Weather lookup is temporarily unavailable."),
                CLOUDY_FORECAST));
        CachingWeatherForecastProvider provider = provider(delegate, ticker);

        assertThrows(
                WeatherForecastUnavailableException.class,
                () -> provider.forecastFor(amsterdam(), STARTS_AT, ENDS_AT, 7));
        assertThrows(
                WeatherForecastUnavailableException.class,
                () -> provider.forecastFor(amsterdam(), STARTS_AT, ENDS_AT, 7));
        assertEquals(1, delegate.calls());

        ticker.advance(Duration.ofSeconds(31));

        WeatherForecast forecast = provider.forecastFor(amsterdam(), STARTS_AT, ENDS_AT, 7);
        assertEquals(80, forecast.weatherAt(STARTS_AT).cloudCoverPercent());
        assertEquals(2, delegate.calls());
    }

    @Test
    void keepsAvailableForecastCachedLongerThanUnavailableForecast() {
        FakeTicker ticker = new FakeTicker();
        CountingProvider delegate = new CountingProvider(CLEAR_FORECAST);
        CachingWeatherForecastProvider provider = provider(delegate, ticker);

        provider.forecastFor(amsterdam(), STARTS_AT, ENDS_AT, 7);

        ticker.advance(Duration.ofSeconds(31));

        provider.forecastFor(amsterdam(), STARTS_AT, ENDS_AT, 7);
        assertEquals(1, delegate.calls());
    }

    @Test
    void coalescesConcurrentIdenticalForecastLookups() throws Exception {
        BlockingProvider delegate = new BlockingProvider(CLEAR_FORECAST);
        CachingWeatherForecastProvider provider = provider(delegate, new FakeTicker());
        int callers = 8;

        try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
            List<Future<WeatherForecast>> futures = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(index -> executor.submit(() -> provider.forecastFor(amsterdam(), STARTS_AT, ENDS_AT, 7)))
                    .toList();

            assertTrue(delegate.awaitFirstCall(), "Delegate was not called.");
            delegate.release();

            for (Future<WeatherForecast> future : futures) {
                assertEquals(20, future.get(2, TimeUnit.SECONDS).weatherAt(STARTS_AT).cloudCoverPercent());
            }
        }

        assertEquals(1, delegate.calls());
    }

    private static CachingWeatherForecastProvider provider(WeatherForecastProvider delegate, FakeTicker ticker) {
        return new CachingWeatherForecastProvider(
                delegate,
                100,
                Duration.ofHours(1),
                Duration.ofSeconds(30),
                ticker);
    }

    private static ResolvedLocation amsterdam() {
        return new ResolvedLocation(
                "moon-service-2759794",
                new ProviderLocationId(LocationProvider.OPEN_METEO, "2759794"),
                "Amsterdam, North Holland, Netherlands",
                52.37403,
                4.88969,
                13,
                ZoneId.of("Europe/Amsterdam"),
                "NL");
    }

    private static ResolvedLocation amsterdamWithExtraCoordinatePrecision() {
        return new ResolvedLocation(
                "moon-service-2759794",
                new ProviderLocationId(LocationProvider.OPEN_METEO, "2759794"),
                "Amsterdam, North Holland, Netherlands",
                52.374031,
                4.889691,
                13,
                ZoneId.of("Europe/Amsterdam"),
                "NL");
    }

    private static HourlyWeather weather(Instant startsAt, int cloudCoverPercent) {
        return new HourlyWeather(
                startsAt,
                cloudCoverPercent,
                5,
                10,
                15,
                0,
                0.0,
                24000,
                2,
                1.0);
    }

    private static final class CountingProvider implements WeatherForecastProvider {
        private final WeatherForecast forecast;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingProvider(WeatherForecast forecast) {
            this.forecast = forecast;
        }

        @Override
        public WeatherForecast forecastFor(
                ResolvedLocation location,
                Instant startsAt,
                Instant endsAt,
                int forecastHorizonDays
        ) {
            calls.incrementAndGet();
            return forecast;
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class SequenceProvider implements WeatherForecastProvider {
        private final List<Object> results;
        private final AtomicInteger calls = new AtomicInteger();

        private SequenceProvider(List<Object> results) {
            this.results = results;
        }

        @Override
        public WeatherForecast forecastFor(
                ResolvedLocation location,
                Instant startsAt,
                Instant endsAt,
                int forecastHorizonDays
        ) {
            int index = calls.getAndIncrement();
            Object result = results.get(Math.min(index, results.size() - 1));
            if (result instanceof WeatherForecastUnavailableException failure) {
                throw failure;
            }
            return (WeatherForecast) result;
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class BlockingProvider implements WeatherForecastProvider {
        private final WeatherForecast forecast;
        private final CountDownLatch firstCall = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        private BlockingProvider(WeatherForecast forecast) {
            this.forecast = forecast;
        }

        @Override
        public WeatherForecast forecastFor(
                ResolvedLocation location,
                Instant startsAt,
                Instant endsAt,
                int forecastHorizonDays
        ) {
            calls.incrementAndGet();
            firstCall.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release weather lookup.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting to release weather lookup.", ex);
            }
            return forecast;
        }

        boolean awaitFirstCall() throws InterruptedException {
            return firstCall.await(2, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
