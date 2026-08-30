package dev.moonservice.scoringprototype.scoring;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.window.MoonWindow;

import java.util.Objects;
import java.util.Optional;

public final class ScoringModel {
    public static final String THIN_CRESCENT_NEAR_CONJUNCTION = "thin_crescent_near_conjunction";
    public static final double NEAR_CONJUNCTION_MAX_ILLUMINATION_PERCENT = 1.0;
    public static final double NEAR_CONJUNCTION_MIN_SEPARATION_DEGREES = 8.0;

    public enum WeatherCodeKind {
        CLEAR,
        MOSTLY_CLEAR,
        PARTLY_CLOUDY,
        OVERCAST,
        FOG,
        RAIN,
        SNOW,
        STORM,
        OTHER_PRECIPITATION,
        UNKNOWN
    }

    private ScoringModel() {
    }

    public static int candidateFit(MoonSample sample) {
        return scoreMoonAltitude(sample.moonAltitudeDegrees()) + scoreSunLight(sample.sunAltitudeDegrees());
    }

    public static Optional<String> ordinaryVisibilityRejectionReason(MoonWindow window) {
        return ordinaryVisibilityRejectionReason(window.suggested());
    }

    public static Optional<String> ordinaryVisibilityRejectionReason(MoonSample sample) {
        if (sample.moonIlluminationPercent() < NEAR_CONJUNCTION_MAX_ILLUMINATION_PERCENT
                && sample.moonSunSeparationDegrees() < NEAR_CONJUNCTION_MIN_SEPARATION_DEGREES) {
            return Optional.of(THIN_CRESCENT_NEAR_CONJUNCTION);
        }
        return Optional.empty();
    }

    public static ComponentScores scoreWindow(MoonWindow window, WeatherFixture weather) {
        return scoreWindow(window, weather, WeatherRanking.BALANCED);
    }

    public static ComponentScores scoreWindow(
            MoonWindow window,
            WeatherFixture weather,
            WeatherRanking weatherRanking
    ) {
        Objects.requireNonNull(weatherRanking, "weatherRanking");
        int moonAltitudeFit = scoreMoonAltitude(window.suggested().moonAltitudeDegrees());
        int sunLightFit = scoreSunLight(window.suggested().sunAltitudeDegrees());
        int moonIlluminationFit = scoreIllumination(window.suggested().moonIlluminationPercent());
        if (!weatherRanking.includesWeatherScores()) {
            return ComponentScores.withoutWeather(moonAltitudeFit, sunLightFit, moonIlluminationFit);
        }
        return ComponentScores.weatherAware(
                moonAltitudeFit,
                sunLightFit,
                moonIlluminationFit,
                scoreWeather(weather, weatherRanking),
                scoreConfidence(weather.forecastAgeHours()));
    }

    public static int scoreMoonAltitude(double altitude) {
        if (altitude < 0.0 || altitude > 90.0) {
            return 0;
        }
        if (altitude <= 1.0) {
            return Math.toIntExact(Math.round(18.0 + altitude * 12.0));
        }
        if (altitude <= 6.0) {
            return 30;
        }
        if (altitude <= 12.0) {
            return Math.toIntExact(Math.round(30.0 - ((altitude - 6.0) / 6.0) * 8.0));
        }
        if (altitude <= 25.0) {
            return Math.toIntExact(Math.round(22.0 - ((altitude - 12.0) / 13.0) * 8.0));
        }
        if (altitude <= 40.0) {
            return Math.toIntExact(Math.round(14.0 - ((altitude - 25.0) / 15.0) * 6.0));
        }
        if (altitude <= 70.0) {
            return Math.toIntExact(Math.round(8.0 - ((altitude - 40.0) / 30.0) * 4.0));
        }
        return Math.toIntExact(Math.round(4.0 - ((altitude - 70.0) / 20.0)));
    }

    public static int scoreSunLight(double sunAltitude) {
        return switch (lightBucket(sunAltitude)) {
            case "golden_hour" -> 25;
            case "civil_twilight" -> 24;
            case "daylight" -> 16;
            case "nautical_twilight" -> 14;
            default -> 7;
        };
    }

    static int scoreIllumination(double percent) {
        if (percent >= 95.0) {
            return 15;
        }
        if (percent >= 85.0) {
            return 12;
        }
        if (percent >= 70.0) {
            return 10;
        }
        if (percent >= 30.0) {
            return 8;
        }
        if (percent >= 5.0) {
            return 6;
        }
        return 4;
    }

    public static int scoreWeather(WeatherFixture weather) {
        return scoreWeather(weather, WeatherRanking.BALANCED);
    }

    private static int scoreWeather(WeatherFixture weather, WeatherRanking weatherRanking) {
        // Aggregate cloud cover contributes up to 13 points; WeatherRanking selects
        // its preferred value. The low-, mid-, and high-cloud fields are not scored.
        int cloudScore = weatherRanking.cloudScore(weather.cloudCoverPercent());

        // Precipitation probability contributes up to 7 points. Start at 7 for 0%,
        // deduct one point per rounded 5-percentage-point step, and floor at zero.
        // Precipitation amount does not contribute to this score. Weather code is
        // used for the factual summary, not for weatherFit.
        int precipScore = Math.max(0, 7 - Math.toIntExact(Math.round(weather.precipitationProbabilityPercent() / 5.0)));

        // Visibility contributes 5 points at 20 km or more, 4 from 15 km up to
        // 20 km, and 2 below 15 km. There is no interpolation between bands.
        int visibilityScore = weather.visibilityMeters() >= 20000 ? 5 : weather.visibilityMeters() >= 15000 ? 4 : 2;

        // The cap enforces the weather component's 25-point allocation: 13 + 7 + 5.
        return Math.min(25, cloudScore + precipScore + visibilityScore);
    }

    public static int scoreConfidence(double forecastAgeHours) {
        if (forecastAgeHours <= 3.0) {
            return 5;
        }
        if (forecastAgeHours <= 12.0) {
            return 4;
        }
        if (forecastAgeHours <= 24.0) {
            return 3;
        }
        return 2;
    }

    public static String confidenceLabel(int score) {
        if (score >= 85) {
            return "high";
        }
        if (score >= 65) {
            return "medium";
        }
        return "low";
    }

    public static String weatherSummary(WeatherFixture weather) {
        return switch (weatherSegmentKind(weather)) {
            case "clear" -> "clear";
            case "mostly_clear" -> "mostly clear";
            case "partly_cloudy" -> "partly cloudy";
            case "mostly_cloudy" -> "mostly cloudy";
            case "overcast" -> "overcast";
            case "precipitation_risk" -> "rain likely";
            case "poor_visibility" -> "fog or low visibility";
            default -> "unknown conditions";
        };
    }

    /**
     * Classifies Open-Meteo {@code weather_code} values against its documented WMO table.
     * Unlisted nonnegative values use defensive classifications so provider drift remains visible.
     */
    public static WeatherCodeKind weatherCodeKind(int weatherCode) {
        return switch (weatherCode) {
            case 0 -> WeatherCodeKind.CLEAR;
            case 1 -> WeatherCodeKind.MOSTLY_CLEAR;
            case 2 -> WeatherCodeKind.PARTLY_CLOUDY;
            case 3 -> WeatherCodeKind.OVERCAST;
            case 45, 48 -> WeatherCodeKind.FOG;
            case 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> WeatherCodeKind.RAIN;
            case 71, 73, 75, 77, 85, 86 -> WeatherCodeKind.SNOW;
            case 95, 96, 99 -> WeatherCodeKind.STORM;
            default -> weatherCode >= 50
                    ? WeatherCodeKind.OTHER_PRECIPITATION
                    : WeatherCodeKind.UNKNOWN;
        };
    }

    public static String weatherSegmentKind(WeatherFixture weather) {
        WeatherCodeKind weatherCodeKind = weatherCodeKind(weather.weatherCode());
        if (weatherCodeKind == WeatherCodeKind.RAIN
                || weatherCodeKind == WeatherCodeKind.SNOW
                || weatherCodeKind == WeatherCodeKind.STORM
                || weatherCodeKind == WeatherCodeKind.OTHER_PRECIPITATION) {
            return "precipitation_risk";
        }
        if (weatherCodeKind == WeatherCodeKind.FOG || weather.visibilityMeters() < 5000) {
            return "poor_visibility";
        }
        if (weatherCodeKind == WeatherCodeKind.OVERCAST || weather.cloudCoverPercent() >= 85) {
            return "overcast";
        }
        if (weather.cloudCoverPercent() >= 65) {
            return "mostly_cloudy";
        }
        if (weatherCodeKind == WeatherCodeKind.PARTLY_CLOUDY || weather.cloudCoverPercent() >= 25) {
            return "partly_cloudy";
        }
        if (weatherCodeKind == WeatherCodeKind.MOSTLY_CLEAR || weather.cloudCoverPercent() >= 10) {
            return "mostly_clear";
        }
        if (weatherCodeKind == WeatherCodeKind.CLEAR) {
            return "clear";
        }
        return "unknown_conditions";
    }

    public static String lightBucket(double sunAltitude) {
        if (sunAltitude >= 6.0) {
            return "daylight";
        }
        if (sunAltitude >= -0.833) {
            return "golden_hour";
        }
        if (sunAltitude >= -6.0) {
            return "civil_twilight";
        }
        if (sunAltitude >= -12.0) {
            return "nautical_twilight";
        }
        return "night";
    }

    public static String exposureBalance(MoonSample sample) {
        String bucket = lightBucket(sample.sunAltitudeDegrees());
        if (bucket.equals("daylight") || bucket.equals("golden_hour")) {
            if (sample.moonIlluminationPercent() >= 70.0) {
                return "moon_detail_easy_foreground_supported";
            }
            if (sample.moonIlluminationPercent() < 5.0) {
                return "thin_crescent_visible_but_subtle";
            }
            return "balanced";
        }
        if (bucket.equals("civil_twilight")) {
            if (sample.moonIlluminationPercent() >= 85.0) {
                return "moon_bright_foreground_risk";
            }
            if (sample.moonIlluminationPercent() < 5.0) {
                return "thin_crescent_visible_but_subtle";
            }
            return "balanced";
        }
        if (sample.moonIlluminationPercent() >= 70.0) {
            return "moon_bright_foreground_risk";
        }
        return "foreground_likely_dark";
    }

    public static String exposureText(MoonSample sample) {
        return switch (exposureBalance(sample)) {
            case "moon_detail_easy_foreground_supported" ->
                    "Ambient light should support foreground detail; expose carefully for the bright Moon.";
            case "thin_crescent_visible_but_subtle" ->
                    "Ambient light may help the scene, but the thin crescent may be subtle.";
            case "moon_bright_foreground_risk" ->
                    "The Moon is bright while foreground light is limited; foreground detail may need careful exposure.";
            case "foreground_likely_dark" ->
                    "Foreground detail is likely limited without silhouette intent, artificial light, or blending.";
            default ->
                    "Ambient light and Moon brightness look reasonably balanced for a natural exposure.";
        };
    }
}
