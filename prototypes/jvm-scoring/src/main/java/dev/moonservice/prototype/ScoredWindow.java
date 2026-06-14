package dev.moonservice.prototype;

record ScoredWindow(
        MoonWindow window,
        WeatherFixture weather,
        ComponentScores components
) {
}
