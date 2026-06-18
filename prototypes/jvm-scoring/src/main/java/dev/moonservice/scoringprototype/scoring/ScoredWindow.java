package dev.moonservice.scoringprototype.scoring;

import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.window.MoonWindow;

public record ScoredWindow(
        MoonWindow window,
        WeatherFixture weather,
        ComponentScores components
) {
}
