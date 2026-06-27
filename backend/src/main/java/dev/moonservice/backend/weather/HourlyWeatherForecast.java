package dev.moonservice.backend.weather;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class HourlyWeatherForecast implements WeatherForecast {
    private static final Duration HOURLY_VALIDITY = Duration.ofHours(1);

    private final List<HourlyWeather> hours;

    public HourlyWeatherForecast(List<HourlyWeather> hours) {
        if (hours == null || hours.isEmpty()) {
            throw new WeatherForecastUnavailableException("Weather forecast did not include hourly records.");
        }
        this.hours = hours.stream()
                .sorted(Comparator.comparing(HourlyWeather::startsAt))
                .toList();
    }

    @Override
    public HourlyWeather weatherAt(Instant instant) {
        if (instant == null) {
            throw new WeatherForecastUnavailableException("Weather lookup instant is required.");
        }

        HourlyWeather previous = null;
        for (HourlyWeather hour : hours) {
            if (hour.startsAt().isAfter(instant)) {
                break;
            }
            previous = hour;
        }

        if (previous == null || !instant.isBefore(previous.startsAt().plus(HOURLY_VALIDITY))) {
            throw new WeatherForecastUnavailableException("No hourly weather record covers " + instant + ".");
        }
        return previous;
    }
}
