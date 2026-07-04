package dev.moonservice.scoringprototype;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Locations;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.scoring.ScoredWindow;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import dev.moonservice.scoringprototype.window.MoonWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V0ScoringPolicyTest {
    private static final Instant SUGGESTED_AT = Instant.parse("2026-06-29T18:30:00Z");
    private static final WeatherFixture FRIENDLY_PARTLY_CLOUDY = WeatherFixture.PRAGUE_PARTLY_CLOUDY;
    private static final WeatherFixture RAINY_OVERCAST = new WeatherFixture(
            95,
            80,
            90,
            95,
            80,
            4.2,
            8000,
            61,
            1.0
    );

    @Test
    void prefersLowMoonWithUsefulAmbientLightOverComparableNightCases() {
        ScoredWindow lowGoldenHour = scoredWindow(4.0, 90.0, 0.0, FRIENDLY_PARTLY_CLOUDY);
        ScoredWindow lowNight = scoredWindow(4.0, 90.0, -13.0, FRIENDLY_PARTLY_CLOUDY);
        ScoredWindow highNight = scoredWindow(55.0, 90.0, -13.0, FRIENDLY_PARTLY_CLOUDY);

        assertRanksAbove(lowGoldenHour, lowNight);
        assertRanksAbove(lowNight, highNight);
        assertEquals(
                "moon_detail_easy_foreground_supported",
                ScoringModel.exposureBalance(lowGoldenHour.window().suggested())
        );
        assertEquals(
                "moon_bright_foreground_risk",
                ScoringModel.exposureBalance(lowNight.window().suggested())
        );
    }

    @Test
    void hostileWeatherStronglyLowersAnOtherwiseStrongWindow() {
        ScoredWindow friendlyWeather = scoredWindow(4.0, 90.0, -3.0, FRIENDLY_PARTLY_CLOUDY);
        ScoredWindow hostileWeather = scoredWindow(4.0, 90.0, -3.0, RAINY_OVERCAST);

        assertRanksAbove(friendlyWeather, hostileWeather);
        assertTrue(hostileWeather.components().weatherFit() <= 5);
        assertEquals("rain likely", ScoringModel.weatherSummary(RAINY_OVERCAST));
    }

    @Test
    void favorableCrescentTwilightRemainsPossibleWhenContextIsGood() {
        ScoredWindow crescentTwilight = scoredWindow(4.0, 3.0, -3.0, FRIENDLY_PARTLY_CLOUDY);
        ScoredWindow rainyFullMoonTwilight = scoredWindow(4.0, 90.0, -3.0, RAINY_OVERCAST);

        assertRanksAbove(crescentTwilight, rainyFullMoonTwilight);
        assertEquals(
                "thin_crescent_visible_but_subtle",
                ScoringModel.exposureBalance(crescentTwilight.window().suggested())
        );
    }

    @Test
    void contextMoonIsAllowedButRanksBelowLowMoonWhenOtherFactsMatch() {
        ScoredWindow lowMoon = scoredWindow(4.0, 70.0, -3.0, FRIENDLY_PARTLY_CLOUDY);
        ScoredWindow contextMoon = scoredWindow(33.0, 70.0, -3.0, FRIENDLY_PARTLY_CLOUDY);

        assertRanksAbove(lowMoon, contextMoon);
        assertTrue(contextMoon.components().moonAltitudeFit() > 0);
    }

    private static ScoredWindow scoredWindow(
            double moonAltitudeDegrees,
            double moonIlluminationPercent,
            double sunAltitudeDegrees,
            WeatherFixture weather
    ) {
        MoonSample suggested = new MoonSample(
                SUGGESTED_AT,
                moonAltitudeDegrees,
                120.0,
                moonIlluminationPercent,
                180.0,
                sunAltitudeDegrees
        );
        MoonSample start = new MoonSample(
                SUGGESTED_AT.minus(Duration.ofMinutes(30)),
                moonAltitudeDegrees,
                116.0,
                moonIlluminationPercent,
                180.0,
                sunAltitudeDegrees
        );
        MoonSample end = new MoonSample(
                SUGGESTED_AT.plus(Duration.ofMinutes(30)),
                moonAltitudeDegrees,
                124.0,
                moonIlluminationPercent,
                180.0,
                sunAltitudeDegrees
        );
        MoonWindow window = new MoonWindow(
                Locations.PRAGUE,
                "policy_case",
                start.instant(),
                end.instant(),
                start.instant(),
                start,
                suggested,
                end,
                end.instant(),
                List.of(start, suggested, end),
                List.of(start, suggested, end)
        );
        return new ScoredWindow(window, weather, ScoringModel.scoreWindow(window, weather));
    }

    private static void assertRanksAbove(ScoredWindow better, ScoredWindow worse) {
        assertTrue(
                better.components().total() > worse.components().total(),
                () -> "Expected " + better.components().total() + " to rank above " + worse.components().total()
        );
    }
}
