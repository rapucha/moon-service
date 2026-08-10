package dev.moonservice.scoringprototype.scoring;

public enum WeatherRanking {
    BALANCED("balanced"),
    PREFER_CLEAR("prefer_clear"),
    IGNORE_WEATHER("ignore_weather");

    private final String wireValue;

    WeatherRanking(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    boolean includesWeatherScores() {
        return this != IGNORE_WEATHER;
    }

    /*
     * Cloud cover contributes at most 13 of the weather component's 25 points.
     * Each curve measures cloud cover from its preferred value, rounds that
     * distance to 5-percentage-point steps, and deducts one point per step.
     * BALANCED prefers 35% cloud cover; PREFER_CLEAR prefers 0%. Both floor the
     * result at zero.
     */
    int cloudScore(int cloudCoverPercent) {
        return switch (this) {
            case BALANCED -> Math.max(
                    0,
                    13 - Math.toIntExact(Math.round(Math.abs(cloudCoverPercent - 35) / 5.0)));
            case PREFER_CLEAR -> Math.max(
                    0,
                    13 - Math.toIntExact(Math.round(cloudCoverPercent / 5.0)));
            case IGNORE_WEATHER -> throw new IllegalStateException("Weather scoring is disabled.");
        };
    }
}
