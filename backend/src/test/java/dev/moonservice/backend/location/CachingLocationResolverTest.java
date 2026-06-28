package dev.moonservice.backend.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CachingLocationResolverTest {
    private static final LocationResolution PRAGUE = LocationResolution.resolved(location(
            "moon-service-3067696",
            "3067696",
            "Prague, Czechia"));
    private static final LocationResolution AMBIGUOUS = LocationResolution.ambiguous(List.of(
            location("moon-service-4409896", "4409896", "Springfield, Missouri, United States"),
            location("moon-service-4250542", "4250542", "Springfield, Illinois, United States")));

    @Test
    void cachesResolvedAmbiguousNotFoundAndTemporarilyUnavailableQueryResults() {
        CountingResolver delegate = new CountingResolver(Map.of(
                "Praha", PRAGUE,
                "Springfield", AMBIGUOUS,
                "Not a place", LocationResolution.notFound(),
                "Provider outage", LocationResolution.temporarilyUnavailable()));
        CachingLocationResolver resolver = resolver(delegate, new FakeTicker());

        assertEquals(LocationResolution.Status.RESOLVED, resolver.resolve(new LocationQuery("Praha")).status());
        assertEquals(LocationResolution.Status.RESOLVED, resolver.resolve(new LocationQuery("Praha")).status());
        assertEquals(LocationResolution.Status.AMBIGUOUS, resolver.resolve(new LocationQuery("Springfield")).status());
        assertEquals(LocationResolution.Status.AMBIGUOUS, resolver.resolve(new LocationQuery("Springfield")).status());
        assertEquals(LocationResolution.Status.NOT_FOUND, resolver.resolve(new LocationQuery("Not a place")).status());
        assertEquals(LocationResolution.Status.NOT_FOUND, resolver.resolve(new LocationQuery("Not a place")).status());
        assertEquals(
                LocationResolution.Status.TEMPORARILY_UNAVAILABLE,
                resolver.resolve(new LocationQuery("Provider outage")).status());
        assertEquals(
                LocationResolution.Status.TEMPORARILY_UNAVAILABLE,
                resolver.resolve(new LocationQuery("Provider outage")).status());

        assertEquals(1, delegate.queryCalls("Praha"));
        assertEquals(1, delegate.queryCalls("Springfield"));
        assertEquals(1, delegate.queryCalls("Not a place"));
        assertEquals(1, delegate.queryCalls("Provider outage"));
    }

    @Test
    void normalizesQueryWhitespaceForCacheKeyAndDelegateCall() {
        CountingResolver delegate = new CountingResolver(Map.of("Praha Czechia", PRAGUE));
        CachingLocationResolver resolver = resolver(delegate, new FakeTicker());

        resolver.resolve(new LocationQuery("  Praha   Czechia  "));
        resolver.resolve(new LocationQuery("Praha Czechia"));

        assertEquals(1, delegate.queryCalls("Praha Czechia"));
    }

    @Test
    void cachesLocationIdLookupsSeparatelyFromQueryLookups() {
        CountingResolver delegate = new CountingResolver(
                Map.of("moon-service-3067696", LocationResolution.notFound()),
                Map.of("moon-service-3067696", PRAGUE));
        CachingLocationResolver resolver = resolver(delegate, new FakeTicker());

        assertEquals(
                LocationResolution.Status.RESOLVED,
                resolver.resolveLocationId(" moon-service-3067696 ").status());
        assertEquals(
                LocationResolution.Status.RESOLVED,
                resolver.resolveLocationId("moon-service-3067696").status());
        assertEquals(LocationResolution.Status.NOT_FOUND, resolver.resolve(new LocationQuery("moon-service-3067696")).status());
        assertEquals(LocationResolution.Status.NOT_FOUND, resolver.resolve(new LocationQuery("moon-service-3067696")).status());

        assertEquals(1, delegate.locationIdCalls("moon-service-3067696"));
        assertEquals(1, delegate.queryCalls("moon-service-3067696"));
    }

    @Test
    void expiresTemporarilyUnavailableResultsSoonerThanResolvedResults() {
        FakeTicker ticker = new FakeTicker();
        SequenceResolver delegate = new SequenceResolver(Map.of(
                "Praha", List.of(PRAGUE),
                "Provider outage", List.of(LocationResolution.temporarilyUnavailable(), PRAGUE)));
        CachingLocationResolver resolver = resolver(delegate, ticker);

        assertEquals(LocationResolution.Status.RESOLVED, resolver.resolve(new LocationQuery("Praha")).status());
        assertEquals(
                LocationResolution.Status.TEMPORARILY_UNAVAILABLE,
                resolver.resolve(new LocationQuery("Provider outage")).status());

        ticker.advance(Duration.ofSeconds(31));

        assertEquals(LocationResolution.Status.RESOLVED, resolver.resolve(new LocationQuery("Praha")).status());
        assertEquals(LocationResolution.Status.RESOLVED, resolver.resolve(new LocationQuery("Provider outage")).status());
        assertEquals(1, delegate.queryCalls("Praha"));
        assertEquals(2, delegate.queryCalls("Provider outage"));
    }

    @Test
    void coalescesConcurrentIdenticalQueryLookups() throws Exception {
        BlockingResolver delegate = new BlockingResolver(PRAGUE);
        CachingLocationResolver resolver = resolver(delegate, new FakeTicker());
        int callers = 8;

        try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
            List<Future<LocationResolution>> futures = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(index -> executor.submit(() -> resolver.resolve(new LocationQuery("Praha"))))
                    .toList();

            assertTrue(delegate.awaitFirstQueryCall(), "Delegate was not called.");
            delegate.release();

            for (Future<LocationResolution> future : futures) {
                assertEquals(LocationResolution.Status.RESOLVED, future.get(2, TimeUnit.SECONDS).status());
            }
        }

        assertEquals(1, delegate.queryCalls());
    }

    private static CachingLocationResolver resolver(LocationResolver delegate, FakeTicker ticker) {
        return new CachingLocationResolver(
                delegate,
                100,
                Duration.ofHours(24),
                Duration.ofHours(24),
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                ticker);
    }

    private static ResolvedLocation location(String locationId, String providerId, String displayName) {
        return new ResolvedLocation(
                locationId,
                new ProviderLocationId(LocationProvider.OPEN_METEO, providerId),
                displayName,
                50.08804,
                14.42076,
                202,
                ZoneId.of("Europe/Prague"),
                "CZ");
    }

    private static final class CountingResolver implements LocationResolver {
        private final Map<String, LocationResolution> queryResults;
        private final Map<String, LocationResolution> locationIdResults;
        private final Map<String, AtomicInteger> queryCalls = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> locationIdCalls = new java.util.concurrent.ConcurrentHashMap<>();

        private CountingResolver(Map<String, LocationResolution> queryResults) {
            this(queryResults, Map.of());
        }

        private CountingResolver(
                Map<String, LocationResolution> queryResults,
                Map<String, LocationResolution> locationIdResults
        ) {
            this.queryResults = queryResults;
            this.locationIdResults = locationIdResults;
        }

        @Override
        public LocationResolution resolve(LocationQuery query) {
            queryCalls.computeIfAbsent(query.text(), ignored -> new AtomicInteger()).incrementAndGet();
            return queryResults.getOrDefault(query.text(), LocationResolution.notFound());
        }

        @Override
        public LocationResolution resolveLocationId(String locationId) {
            locationIdCalls.computeIfAbsent(locationId, ignored -> new AtomicInteger()).incrementAndGet();
            return locationIdResults.getOrDefault(locationId, LocationResolution.notFound());
        }

        int queryCalls(String query) {
            return queryCalls.getOrDefault(query, new AtomicInteger()).get();
        }

        int locationIdCalls(String locationId) {
            return locationIdCalls.getOrDefault(locationId, new AtomicInteger()).get();
        }
    }

    private static final class SequenceResolver implements LocationResolver {
        private final Map<String, List<LocationResolution>> queryResults;
        private final Map<String, AtomicInteger> queryCalls = new java.util.concurrent.ConcurrentHashMap<>();

        private SequenceResolver(Map<String, List<LocationResolution>> queryResults) {
            this.queryResults = queryResults;
        }

        @Override
        public LocationResolution resolve(LocationQuery query) {
            int index = queryCalls.computeIfAbsent(query.text(), ignored -> new AtomicInteger()).getAndIncrement();
            List<LocationResolution> results = queryResults.get(query.text());
            if (results == null || results.isEmpty()) {
                return LocationResolution.notFound();
            }
            return results.get(Math.min(index, results.size() - 1));
        }

        int queryCalls(String query) {
            return queryCalls.getOrDefault(query, new AtomicInteger()).get();
        }
    }

    private static final class BlockingResolver implements LocationResolver {
        private final LocationResolution result;
        private final CountDownLatch firstQueryCall = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger queryCalls = new AtomicInteger();

        private BlockingResolver(LocationResolution result) {
            this.result = result;
        }

        @Override
        public LocationResolution resolve(LocationQuery query) {
            queryCalls.incrementAndGet();
            firstQueryCall.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release provider lookup.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting to release provider lookup.", ex);
            }
            return result;
        }

        boolean awaitFirstQueryCall() throws InterruptedException {
            return firstQueryCall.await(2, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        int queryCalls() {
            return queryCalls.get();
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
