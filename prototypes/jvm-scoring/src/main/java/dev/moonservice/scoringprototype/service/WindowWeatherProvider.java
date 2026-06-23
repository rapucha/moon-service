package dev.moonservice.scoringprototype.service;

import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.window.MoonWindow;

@FunctionalInterface
public interface WindowWeatherProvider {
    WeatherFixture weatherFor(MoonWindow window);

    static WindowWeatherProvider fixed(WeatherFixture weather) {
        return window -> weather;
    }
}
