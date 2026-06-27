package dev.moonservice.backend.weather;

import dev.moonservice.backend.location.ResolvedLocation;

import java.time.Instant;

public interface WeatherForecastProvider {
    WeatherForecast forecastFor(
            ResolvedLocation location,
            Instant startsAt,
            Instant endsAt,
            int forecastHorizonDays
    );
}
