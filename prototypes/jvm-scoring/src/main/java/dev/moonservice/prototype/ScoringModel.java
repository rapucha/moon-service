package dev.moonservice.prototype;

import java.util.ArrayList;
import java.util.List;

final class ScoringModel {
    private ScoringModel() {
    }

    static List<String> hardFilterReasons(MoonWindow window, WeatherFixture weather, PrototypeConfig config) {
        List<String> reasons = new ArrayList<>();
        if (window.peak().moonAltitudeDegrees() < 0.0) {
            reasons.add("moon_below_horizon");
        }
        if (window.peak().moonAltitudeDegrees() > config.maxMoonAltitudeDegrees()) {
            reasons.add("moon_too_high_for_low_moon_mode");
        }
        if (weather.cloudCoverPercent() >= 90) {
            reasons.add("overcast");
        }
        if (weather.precipitationProbabilityPercent() >= 30) {
            reasons.add("high_precipitation_probability");
        }
        if (weather.visibilityMeters() < 10000) {
            reasons.add("low_visibility");
        }
        return reasons;
    }

    static int candidateFit(MoonSample sample) {
        return scoreMoonAltitude(sample.moonAltitudeDegrees()) + scoreSunLight(sample.sunAltitudeDegrees());
    }

    static ComponentScores scoreWindow(MoonWindow window, WeatherFixture weather) {
        return new ComponentScores(
                scoreMoonAltitude(window.peak().moonAltitudeDegrees()),
                scoreSunLight(window.peak().sunAltitudeDegrees()),
                scoreIllumination(window.peak().moonIlluminationPercent()),
                scoreWeather(weather),
                scoreConfidence(weather.forecastAgeHours())
        );
    }

    static int scoreMoonAltitude(double altitude) {
        if (altitude < 0.0 || altitude > 12.0) {
            return 0;
        }
        if (altitude >= 1.0 && altitude <= 6.0) {
            return 30;
        }
        if (altitude < 1.0) {
            return Math.toIntExact(Math.round(18.0 + altitude * 6.0));
        }
        return Math.toIntExact(Math.round(30.0 - ((altitude - 6.0) / 6.0) * 12.0));
    }

    static int scoreSunLight(double sunAltitude) {
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

    static int scoreWeather(WeatherFixture weather) {
        int cloudScore = Math.max(0, 13 - Math.toIntExact(Math.round(Math.abs(weather.cloudCoverPercent() - 35) / 5.0)));
        int precipScore = Math.max(0, 7 - Math.toIntExact(Math.round(weather.precipitationProbabilityPercent() / 5.0)));
        int visibilityScore = weather.visibilityMeters() >= 20000 ? 5 : weather.visibilityMeters() >= 15000 ? 4 : 2;
        return Math.min(25, cloudScore + precipScore + visibilityScore);
    }

    static int scoreConfidence(double forecastAgeHours) {
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

    static String confidenceLabel(int score) {
        if (score >= 85) {
            return "high";
        }
        if (score >= 65) {
            return "medium";
        }
        return "low";
    }

    static String weatherSummary(WeatherFixture weather) {
        if (weather.weatherCode() == 0 || weather.weatherCode() == 1) {
            return "clear to mostly clear";
        }
        if (weather.weatherCode() == 2 || weather.weatherCode() == 3) {
            return "partly cloudy";
        }
        if (weather.weatherCode() >= 50) {
            return "rain likely";
        }
        return "mixed conditions";
    }

    static String lightBucket(double sunAltitude) {
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

    static String exposureBalance(MoonSample sample) {
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

    static String exposureText(MoonSample sample) {
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
