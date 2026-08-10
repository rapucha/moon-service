package dev.moonservice.scoringprototype;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Locations;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.scoring.ComponentScores;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import dev.moonservice.scoringprototype.scoring.WeatherRanking;
import dev.moonservice.scoringprototype.window.MoonWindow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void preferClearMovesTheCloudOptimumWithoutChangingTheWeatherAllocation() {
        MoonWindow window = scoringWindow();
        WeatherFixture clear = weather(0, 1.0);
        WeatherFixture partlyCloudy = weather(35, 1.0);

        ComponentScores balancedClear =
                ScoringModel.scoreWindow(window, clear, WeatherRanking.BALANCED);
        ComponentScores balancedPartlyCloudy =
                ScoringModel.scoreWindow(window, partlyCloudy, WeatherRanking.BALANCED);
        ComponentScores preferClearClear =
                ScoringModel.scoreWindow(window, clear, WeatherRanking.PREFER_CLEAR);
        ComponentScores preferClearPartlyCloudy =
                ScoringModel.scoreWindow(window, partlyCloudy, WeatherRanking.PREFER_CLEAR);

        assertEquals(18, balancedClear.weatherFit());
        assertEquals(25, balancedPartlyCloudy.weatherFit());
        assertEquals(25, preferClearClear.weatherFit());
        assertEquals(18, preferClearPartlyCloudy.weatherFit());
        assertTrue(balancedPartlyCloudy.total() > balancedClear.total());
        assertTrue(preferClearClear.total() > preferClearPartlyCloudy.total());
        assertEquals(100, preferClearClear.componentMaximum());
        assertEquals(List.of(), preferClearClear.excludedComponents());
    }

    @Test
    void ignoreWeatherNormalizesTheMoonAndLightBasisAndIsWeatherIndependent() {
        MoonWindow window = scoringWindow();

        ComponentScores clear =
                ScoringModel.scoreWindow(window, weather(0, 1.0), WeatherRanking.IGNORE_WEATHER);
        ComponentScores staleOvercast =
                ScoringModel.scoreWindow(window, weather(100, 48.0), WeatherRanking.IGNORE_WEATHER);
        ComponentScores perfect = ScoringModel.scoreWindow(
                scoringWindow(1.0, 100.0, 0.0),
                weather(100, 48.0),
                WeatherRanking.IGNORE_WEATHER);

        assertEquals(clear, staleOvercast);
        assertEquals(48, clear.componentPoints());
        assertEquals(70, clear.componentMaximum());
        assertEquals(69, clear.total());
        assertNull(clear.weatherFit());
        assertNull(clear.forecastConfidence());
        assertEquals(List.of("weatherFit", "forecastConfidence"), clear.excludedComponents());
        assertEquals(70, perfect.componentPoints());
        assertEquals(100, perfect.total());
    }

    @Test
    void omittedWeatherRankingIsExactlyBalanced() {
        MoonWindow window = scoringWindow();

        assertEquals(
                ScoringModel.scoreWindow(
                        window, WeatherFixture.PRAGUE_PARTLY_CLOUDY, WeatherRanking.BALANCED),
                ScoringModel.scoreWindow(window, WeatherFixture.PRAGUE_PARTLY_CLOUDY));
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

    private static MoonWindow scoringWindow() {
        return scoringWindow(9.0, 50.0, -8.0);
    }

    private static MoonWindow scoringWindow(
            double moonAltitude,
            double illumination,
            double sunAltitude
    ) {
        MoonSample sample = sample(moonAltitude, illumination, sunAltitude);
        return new MoonWindow(
                Locations.PRAGUE,
                "moonrise_low",
                sample.instant(),
                sample.instant(),
                sample.instant(),
                sample,
                sample,
                sample,
                sample.instant(),
                List.of(sample),
                List.of(sample));
    }

    private static WeatherFixture weather(int cloudCoverPercent, double forecastAgeHours) {
        return new WeatherFixture(
                cloudCoverPercent,
                cloudCoverPercent,
                0,
                0,
                0,
                0.0,
                20000,
                2,
                forecastAgeHours);
    }
}
