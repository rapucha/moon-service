package dev.moonservice.backend.weather;

import java.time.Instant;

@FunctionalInterface
public interface WeatherForecast {
    HourlyWeather weatherAt(Instant instant);

    static WeatherForecast fixed(HourlyWeather weather) {
        return instant -> weather;
    }
}
