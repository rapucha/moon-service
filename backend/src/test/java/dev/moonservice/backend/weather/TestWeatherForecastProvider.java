package dev.moonservice.backend.weather;

import dev.moonservice.backend.location.ResolvedLocation;

import java.time.Instant;

public final class TestWeatherForecastProvider implements WeatherForecastProvider {
    private final HourlyWeather weather;

    public TestWeatherForecastProvider() {
        this(new HourlyWeather(
                Instant.parse("2026-06-29T00:00:00Z"),
                28,
                8,
                18,
                35,
                4,
                0.0,
                24000,
                2,
                1.0));
    }

    public TestWeatherForecastProvider(HourlyWeather weather) {
        this.weather = weather;
    }

    @Override
    public WeatherForecast forecastFor(
            ResolvedLocation location,
            Instant startsAt,
            Instant endsAt,
            int forecastHorizonDays
    ) {
        return instant -> weather;
    }
}
