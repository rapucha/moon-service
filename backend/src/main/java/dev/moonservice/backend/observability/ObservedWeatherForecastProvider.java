package dev.moonservice.backend.observability;

import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.weather.WeatherForecast;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastUnavailableException;

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
            Instant endsAt,
            int forecastHorizonDays
    ) {
        long started = System.nanoTime();
        try {
            WeatherForecast forecast = delegate.forecastFor(location, startsAt, endsAt, forecastHorizonDays);
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
