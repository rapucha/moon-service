package dev.moonservice.backend.observability;

import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;

import java.util.Objects;

public final class ObservedLocationResolver implements LocationResolver {
    private final LocationResolver delegate;
    private final OpenMeteoObservability.ProviderMetrics metrics;

    public ObservedLocationResolver(
            LocationResolver delegate,
            OpenMeteoObservability.ProviderMetrics metrics
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public LocationResolution resolve(LocationQuery query) {
        long started = System.nanoTime();
        try {
            LocationResolution resolution = delegate.resolve(query);
            metrics.recordLocationOutcome(resolution.status(), elapsedNanos(started));
            return resolution;
        } catch (RuntimeException ex) {
            metrics.recordLocationOutcome(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, elapsedNanos(started));
            throw ex;
        }
    }

    @Override
    public LocationResolution resolveLocationId(String locationId) {
        long started = System.nanoTime();
        try {
            LocationResolution resolution = delegate.resolveLocationId(locationId);
            metrics.recordLocationOutcome(resolution.status(), elapsedNanos(started));
            return resolution;
        } catch (RuntimeException ex) {
            metrics.recordLocationOutcome(LocationResolution.Status.TEMPORARILY_UNAVAILABLE, elapsedNanos(started));
            throw ex;
        }
    }

    private static long elapsedNanos(long started) {
        return System.nanoTime() - started;
    }
}
