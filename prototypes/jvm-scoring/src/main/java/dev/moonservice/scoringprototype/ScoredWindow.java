package dev.moonservice.scoringprototype;

record ScoredWindow(
        MoonWindow window,
        WeatherFixture weather,
        ComponentScores components
) {
}
