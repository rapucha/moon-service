package dev.moonservice.scoringprototype;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoringModelTest {
    @Test
    void scoresMoonAltitudeForLowMoonUseCase() {
        assertEquals(0, ScoringModel.scoreMoonAltitude(-0.1));
        assertEquals(18, ScoringModel.scoreMoonAltitude(0.0));
        assertEquals(30, ScoringModel.scoreMoonAltitude(1.0));
        assertEquals(30, ScoringModel.scoreMoonAltitude(6.0));
        assertEquals(24, ScoringModel.scoreMoonAltitude(9.0));
        assertEquals(18, ScoringModel.scoreMoonAltitude(12.0));
        assertEquals(0, ScoringModel.scoreMoonAltitude(12.1));
    }

    @Test
    void scoresLightBucketsWithTwilightPreference() {
        assertEquals("daylight", ScoringModel.lightBucket(6.0));
        assertEquals("golden_hour", ScoringModel.lightBucket(0.0));
        assertEquals("civil_twilight", ScoringModel.lightBucket(-4.0));
        assertEquals("nautical_twilight", ScoringModel.lightBucket(-8.0));
        assertEquals("night", ScoringModel.lightBucket(-13.0));

        assertEquals(16, ScoringModel.scoreSunLight(6.0));
        assertEquals(25, ScoringModel.scoreSunLight(0.0));
        assertEquals(24, ScoringModel.scoreSunLight(-4.0));
        assertEquals(14, ScoringModel.scoreSunLight(-8.0));
        assertEquals(7, ScoringModel.scoreSunLight(-13.0));
    }

    @Test
    void scoresFixtureWeatherToMaximumFit() {
        assertEquals(24, ScoringModel.scoreWeather(WeatherFixture.PRAGUE_PARTLY_CLOUDY));
        assertEquals("partly cloudy", ScoringModel.weatherSummary(WeatherFixture.PRAGUE_PARTLY_CLOUDY));
        assertEquals(5, ScoringModel.scoreConfidence(1.0));
    }

    @Test
    void returnsExposureBalanceVocabularyFromPythonContract() {
        assertEquals(
                "moon_detail_easy_foreground_supported",
                ScoringModel.exposureBalance(sample(4.0, 90.0, 0.0))
        );
        assertEquals(
                "moon_bright_foreground_risk",
                ScoringModel.exposureBalance(sample(4.0, 90.0, -4.0))
        );
        assertEquals(
                "thin_crescent_visible_but_subtle",
                ScoringModel.exposureBalance(sample(4.0, 3.0, -3.0))
        );
        assertEquals(
                "foreground_likely_dark",
                ScoringModel.exposureBalance(sample(4.0, 30.0, -13.0))
        );
    }

    private static MoonSample sample(double moonAltitude, double illumination, double sunAltitude) {
        return new MoonSample(
                Instant.parse("2026-06-29T00:00:00Z"),
                moonAltitude,
                120.0,
                illumination,
                sunAltitude
        );
    }
}
