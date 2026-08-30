package dev.moonservice.backend.weather;

import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.observability.OpenMeteoObservability;

import java.time.Instant;
import java.util.Objects;

public final class ObservedWeatherForecastProvider implements WeatherForecastProvider {
    private final WeatherForecastProvider delegate;
    private final OpenMeteoObservability.ProviderMetrics metrics;

    public ObservedWeatherForecastProvider(
            WeatherForecastProvider delegate,
            OpenMeteoObservability.ProviderMetrics metrics
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public WeatherForecast forecastFor(
            ResolvedLocation location,
            Instant startsAt,
            Instant endsAt
    ) {
        long started = System.nanoTime();
        try {
            WeatherForecast forecast = delegate.forecastFor(location, startsAt, endsAt);
            metrics.recordWeatherAvailable(elapsedNanos(started));
            return forecast;
        } catch (WeatherForecastUnavailableException ex) {
            metrics.recordWeatherUnavailable(elapsedNanos(started));
            throw ex;
        }
    }

    private static long elapsedNanos(long started) {
        return System.nanoTime() - started;
    }
}
