package dev.moonservice.scoringprototype;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoringModelTest {
    @Test
    void scoresMoonAltitudeForLowMoonUseCase() {
        assertEquals(0, ScoringModel.scoreMoonAltitude(-0.1));
        assertEquals(18, ScoringModel.scoreMoonAltitude(0.0));
        assertEquals(30, ScoringModel.scoreMoonAltitude(1.0));
        assertEquals(30, ScoringModel.scoreMoonAltitude(6.0));
        assertEquals(26, ScoringModel.scoreMoonAltitude(9.0));
        assertEquals(22, ScoringModel.scoreMoonAltitude(12.0));
        assertEquals(14, ScoringModel.scoreMoonAltitude(25.0));
        assertEquals(11, ScoringModel.scoreMoonAltitude(33.0));
        assertEquals(8, ScoringModel.scoreMoonAltitude(40.0));
        assertEquals(7, ScoringModel.scoreMoonAltitude(50.0));
        assertEquals(4, ScoringModel.scoreMoonAltitude(70.0));
        assertEquals(3, ScoringModel.scoreMoonAltitude(90.0));
        assertEquals(0, ScoringModel.scoreMoonAltitude(90.1));
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
    void summarizesOvercastCloudAsOvercast() {
        WeatherFixture heavyCloud = new WeatherFixture(
                89,
                80,
                90,
                70,
                5,
                0.0,
                20000,
                2,
                1.0
        );

        assertEquals("overcast", ScoringModel.weatherSummary(heavyCloud));
        assertEquals("overcast", ScoringModel.weatherSegmentKind(heavyCloud));
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

    @Test
    void computesTopocentricMoonSunSeparationFromAltitudeAndAzimuth() {
        MoonSample sample = sample(4.0, 0.2, 0.0, 61.0, 63.0);

        assertEquals(4.47, sample.moonSunSeparationDegrees(), 0.01);
    }

    @Test
    void rejectsNearConjunctionThinCrescentsButAllowsVisibleCrescentCases() {
        MoonSample nearConjunction = sample(4.0, 0.2, 0.0, 61.0, 63.0);
        MoonSample ordinaryCrescent = sample(4.0, 3.0, -3.0, 120.0, 90.0);
        MoonSample separatedThinCrescent = sample(4.0, 0.2, -3.0, 120.0, 90.0);

        assertEquals(
                ScoringModel.THIN_CRESCENT_NEAR_CONJUNCTION,
                ScoringModel.ordinaryVisibilityRejectionReason(nearConjunction).orElseThrow()
        );
        assertFalse(ScoringModel.ordinaryVisibilityRejectionReason(ordinaryCrescent).isPresent());
        assertFalse(ScoringModel.ordinaryVisibilityRejectionReason(separatedThinCrescent).isPresent());
        assertTrue(ordinaryCrescent.moonSunSeparationDegrees() > ScoringModel.NEAR_CONJUNCTION_MIN_SEPARATION_DEGREES);
    }

    private static MoonSample sample(double moonAltitude, double illumination, double sunAltitude) {
        return sample(moonAltitude, illumination, sunAltitude, 120.0, 90.0);
    }

    private static MoonSample sample(
            double moonAltitude,
            double illumination,
            double sunAltitude,
            double moonAzimuth,
            double sunAzimuth
    ) {
        return new MoonSample(
                Instant.parse("2026-06-29T00:00:00Z"),
                moonAltitude,
                moonAzimuth,
                illumination,
                180.0,
                null,
                sunAltitude,
                sunAzimuth
        );
    }
}
