package dev.moonservice.backend.observability;

import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.openmeteo.OpenMeteoFailureKind;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class OpenMeteoObservability {
    public static final ProviderOperationDefinition GEOCODING_OPERATION = new ProviderOperationDefinition(
            "open-meteo-geocoding",
            "open-meteo",
            "geocoding",
            ProviderQuotaLimits.unknown());
    public static final ProviderOperationDefinition WEATHER_OPERATION = new ProviderOperationDefinition(
            "open-meteo-weather",
            "open-meteo",
            "weather",
            ProviderQuotaLimits.unknown());

    private final ProviderMetrics geocoding;
    private final ProviderMetrics weather;

    public OpenMeteoObservability(ProviderQuotaMonitor quotaMonitor) {
        Objects.requireNonNull(quotaMonitor, "quotaMonitor");
        this.geocoding = new ProviderMetrics(quotaMonitor.operation(GEOCODING_OPERATION.id()));
        this.weather = new ProviderMetrics(quotaMonitor.operation(WEATHER_OPERATION.id()));
    }

    public ProviderMetrics geocoding() {
        return geocoding;
    }

    public ProviderMetrics weather() {
        return weather;
    }

    public GeocodingSnapshot geocodingSnapshot() {
        ProviderSnapshot snapshot = geocoding.snapshot();
        return new GeocodingSnapshot(
                snapshot.calls(),
                snapshot.resolved(),
                snapshot.ambiguous(),
                snapshot.notFound(),
                snapshot.temporarilyUnavailable(),
                snapshot.retries(),
                snapshot.timeouts(),
                snapshot.rateLimited(),
                snapshot.averageLatencyMillis(),
                snapshot.maxLatencyMillis());
    }

    public WeatherSnapshot weatherSnapshot() {
        ProviderSnapshot snapshot = weather.snapshot();
        return new WeatherSnapshot(
                snapshot.calls(),
                snapshot.available(),
                snapshot.temporarilyUnavailable(),
                snapshot.retries(),
                snapshot.timeouts(),
                snapshot.rateLimited(),
                snapshot.averageLatencyMillis(),
                snapshot.maxLatencyMillis());
    }

    public static final class ProviderMetrics {
        private final ProviderQuotaMonitor.ProviderQuotaCounter quotaCounter;
        private final LongAdder calls = new LongAdder();
        private final LongAdder resolved = new LongAdder();
        private final LongAdder ambiguous = new LongAdder();
        private final LongAdder notFound = new LongAdder();
        private final LongAdder available = new LongAdder();
        private final LongAdder temporarilyUnavailable = new LongAdder();
        private final LongAdder retries = new LongAdder();
        private final LongAdder timeouts = new LongAdder();
        private final LongAdder rateLimited = new LongAdder();
        private final LongAdder totalLatencyNanos = new LongAdder();
        private final AtomicLong maxLatencyNanos = new AtomicLong();

        private ProviderMetrics(ProviderQuotaMonitor.ProviderQuotaCounter quotaCounter) {
            this.quotaCounter = Objects.requireNonNull(quotaCounter, "quotaCounter");
        }

        public void recordProviderCall() {
            quotaCounter.recordCall();
        }

        public void recordLocationOutcome(LocationResolution.Status status, long durationNanos) {
            recordCall(durationNanos);
            switch (Objects.requireNonNull(status, "status")) {
                case RESOLVED -> resolved.increment();
                case AMBIGUOUS -> ambiguous.increment();
                case NOT_FOUND -> notFound.increment();
                case TEMPORARILY_UNAVAILABLE -> temporarilyUnavailable.increment();
            }
        }

        public void recordWeatherAvailable(long durationNanos) {
            recordCall(durationNanos);
            available.increment();
        }

        public void recordWeatherUnavailable(long durationNanos) {
            recordCall(durationNanos);
            temporarilyUnavailable.increment();
        }

        public void recordRetry() {
            retries.increment();
        }

        public void recordTransportFailure(OpenMeteoFailureKind kind) {
            switch (Objects.requireNonNull(kind, "kind")) {
                case RATE_LIMIT -> rateLimited.increment();
                case TIMEOUT -> timeouts.increment();
                case TRANSIENT_HTTP_FAILURE, NON_RETRYABLE_HTTP_FAILURE, IO_FAILURE -> {
                    // Outcome counters capture final provider availability.
                }
            }
        }

        ProviderSnapshot snapshot() {
            long callsSnapshot = calls.sum();
            long totalLatencySnapshot = totalLatencyNanos.sum();
            return new ProviderSnapshot(
                    callsSnapshot,
                    resolved.sum(),
                    ambiguous.sum(),
                    notFound.sum(),
                    available.sum(),
                    temporarilyUnavailable.sum(),
                    retries.sum(),
                    timeouts.sum(),
                    rateLimited.sum(),
                    callsSnapshot == 0 ? 0.0 : nanosToMillis(totalLatencySnapshot) / callsSnapshot,
                    Math.round(nanosToMillis(maxLatencyNanos.get())));
        }

        private void recordCall(long durationNanos) {
            long normalizedDuration = Math.max(0, durationNanos);
            calls.increment();
            totalLatencyNanos.add(normalizedDuration);
            updateMaxLatency(normalizedDuration);
        }

        private void updateMaxLatency(long durationNanos) {
            long current;
            do {
                current = maxLatencyNanos.get();
                if (durationNanos <= current) {
                    return;
                }
            } while (!maxLatencyNanos.compareAndSet(current, durationNanos));
        }

        private static double nanosToMillis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }

    private record ProviderSnapshot(
            long calls,
            long resolved,
            long ambiguous,
            long notFound,
            long available,
            long temporarilyUnavailable,
            long retries,
            long timeouts,
            long rateLimited,
            double averageLatencyMillis,
            long maxLatencyMillis
    ) {
    }

    public record GeocodingSnapshot(
            long calls,
            long resolved,
            long ambiguous,
            long notFound,
            long temporarilyUnavailable,
            long retries,
            long timeouts,
            long rateLimited,
            double averageLatencyMillis,
            long maxLatencyMillis
    ) {
    }

    public record WeatherSnapshot(
            long calls,
            long available,
            long temporarilyUnavailable,
            long retries,
            long timeouts,
            long rateLimited,
            double averageLatencyMillis,
            long maxLatencyMillis
    ) {
    }
}
