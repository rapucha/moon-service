package dev.moonservice.scoringprototype.scoring;

import java.util.List;

public record ComponentScores(
        int moonAltitudeFit,
        int sunLightFit,
        int moonIlluminationFit,
        Integer weatherFit,
        Integer forecastConfidence,
        int componentMaximum,
        List<String> excludedComponents
) {
    private static final int WEATHER_AWARE_MAXIMUM = 100;
    private static final int MOON_AND_LIGHT_MAXIMUM = 70;
    private static final List<String> WEATHER_EXCLUSIONS =
            List.of("weatherFit", "forecastConfidence");

    public ComponentScores {
        excludedComponents = List.copyOf(excludedComponents);
    }

    static ComponentScores weatherAware(
            int moonAltitudeFit,
            int sunLightFit,
            int moonIlluminationFit,
            int weatherFit,
            int forecastConfidence
    ) {
        return new ComponentScores(
                moonAltitudeFit,
                sunLightFit,
                moonIlluminationFit,
                weatherFit,
                forecastConfidence,
                WEATHER_AWARE_MAXIMUM,
                List.of());
    }

    static ComponentScores withoutWeather(
            int moonAltitudeFit,
            int sunLightFit,
            int moonIlluminationFit
    ) {
        return new ComponentScores(
                moonAltitudeFit,
                sunLightFit,
                moonIlluminationFit,
                null,
                null,
                MOON_AND_LIGHT_MAXIMUM,
                WEATHER_EXCLUSIONS);
    }

    public int componentPoints() {
        return moonAltitudeFit
                + sunLightFit
                + moonIlluminationFit
                + (weatherFit == null ? 0 : weatherFit)
                + (forecastConfidence == null ? 0 : forecastConfidence);
    }

    public int total() {
        return Math.toIntExact(Math.round(componentPoints() * 100.0 / componentMaximum));
    }
}
