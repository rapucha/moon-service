package dev.moonservice.scoringprototype.service;

import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.window.MoonWindow;

/**
 * Kept as a functional interface so backend adapters and prototype tests can
 * provide per-window weather with lambdas.
 */
@FunctionalInterface
public interface WindowWeatherProvider {
    WeatherFixture weatherFor(MoonWindow window);

    static WindowWeatherProvider sameWeatherForEveryWindow(WeatherFixture weather) {
        return window -> weather;
    }
}
