package dev.moonservice.backend.weather;

import dev.moonservice.scoringprototype.fixture.WeatherFixture;

import java.time.Instant;

public record HourlyWeather(
        Instant startsAt,
        int cloudCoverPercent,
        int lowCloudCoverPercent,
        int midCloudCoverPercent,
        int highCloudCoverPercent,
        int precipitationProbabilityPercent,
        double precipitationMm,
        int visibilityMeters,
        int weatherCode,
        double forecastAgeHours
) {
    public HourlyWeather {
        if (startsAt == null) {
            throw new IllegalArgumentException("startsAt is required.");
        }
        cloudCoverPercent = percent(cloudCoverPercent, "cloudCoverPercent");
        lowCloudCoverPercent = percent(lowCloudCoverPercent, "lowCloudCoverPercent");
        midCloudCoverPercent = percent(midCloudCoverPercent, "midCloudCoverPercent");
        highCloudCoverPercent = percent(highCloudCoverPercent, "highCloudCoverPercent");
        precipitationProbabilityPercent = percent(
                precipitationProbabilityPercent,
                "precipitationProbabilityPercent");
        if (!Double.isFinite(precipitationMm) || precipitationMm < 0.0) {
            throw new IllegalArgumentException("precipitationMm must be a finite non-negative value.");
        }
        if (visibilityMeters < 0) {
            throw new IllegalArgumentException("visibilityMeters must be zero or greater.");
        }
        if (!Double.isFinite(forecastAgeHours) || forecastAgeHours < 0.0) {
            throw new IllegalArgumentException("forecastAgeHours must be a finite non-negative value.");
        }
    }

    public WeatherFixture toWeatherFixture() {
        return new WeatherFixture(
                cloudCoverPercent,
                lowCloudCoverPercent,
                midCloudCoverPercent,
                highCloudCoverPercent,
                precipitationProbabilityPercent,
                precipitationMm,
                visibilityMeters,
                weatherCode,
                forecastAgeHours
        );
    }

    private static int percent(int value, String fieldName) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(fieldName + " must be between 0 and 100.");
        }
        return value;
    }
}
