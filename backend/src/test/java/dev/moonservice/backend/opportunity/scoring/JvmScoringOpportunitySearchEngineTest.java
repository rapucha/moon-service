package dev.moonservice.backend.opportunity.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.moonservice.backend.location.LocationProvider;
import dev.moonservice.backend.location.ProviderLocationId;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.backend.weather.HourlyWeather;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.scoringprototype.PreviewEvaluator;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

class JvmScoringOpportunitySearchEngineTest {
    @Test
    void scoresResolvedLocationCoordinatesWithoutFixtureLocationId() {
        JvmScoringOpportunitySearchEngine engine = engineWithPartlyCloudyWeather();

        OpportunitySearchResponse response = engine.search(
                amsterdam(),
                new OpportunitySearchRequest("amsterdam-nl", "2026-06-29", 7, 90.0, 5));

        assertEquals("ok", response.status());
        assertEquals("amsterdam-nl", response.location().id());
        assertEquals("Amsterdam, North Holland, Netherlands", response.location().displayName());
        assertEquals("Europe/Amsterdam", response.location().timezone());
        assertFalse(response.opportunities().isEmpty());
        assertTrue(response.opportunities().getFirst().id().startsWith("amsterdam-nl-"));
        assertTrue(response.opportunities().getFirst().links().get("ics").startsWith("/o/amsterdam-nl-"));
    }

    @Test
    void scoresResolvedLocationWithWeatherForecastProviderData() {
        AtomicReference<ResolvedLocation> requestedLocation = new AtomicReference<>();
        AtomicReference<Integer> requestedForecastHorizonDays = new AtomicReference<>();
        WeatherForecastProvider provider = (location, startsAt, endsAt, forecastHorizonDays) -> {
            requestedLocation.set(location);
            requestedForecastHorizonDays.set(forecastHorizonDays);
            HourlyWeather weather = new HourlyWeather(
                    startsAt,
                    82,
                    70,
                    45,
                    20,
                    35,
                    0.8,
                    12000,
                    61,
                    2.0);
            return instant -> weather;
        };
        JvmScoringOpportunitySearchEngine engine = new JvmScoringOpportunitySearchEngine(
                new PreviewEvaluator(),
                provider);

        OpportunitySearchResponse response = engine.search(
                amsterdam(),
                new OpportunitySearchRequest("amsterdam-nl", "2026-06-29", 7, 90.0, 5));

        OpportunitySearchResponse.Weather weather = response.opportunities().getFirst().weather();
        assertEquals(amsterdam(), requestedLocation.get());
        assertEquals(7, requestedForecastHorizonDays.get());
        assertEquals(82, weather.cloudCoverMeanPercent());
        assertEquals(70, weather.lowCloudCoverMaxPercent());
        assertEquals(35, weather.precipitationProbabilityMaxPercent());
        assertEquals(0.8, weather.precipitationMm());
        assertEquals(12000, weather.visibilityMinMeters());
        assertEquals(61, weather.weatherCode());
        assertEquals("rain likely", weather.summary());
    }

    @Test
    void liveSearchKeepsOngoingWindowAndScoresRemainingSuggestion() {
        Instant notBefore = Instant.parse("2026-06-29T01:30:00Z");
        WeatherForecastProvider provider = (location, startsAt, endsAt, forecastHorizonDays) -> instant -> {
            if (instant.isBefore(notBefore)) {
                return new HourlyWeather(instant, 100, 100, 100, 100, 90, 3.0, 8000, 61, 2.0);
            }
            return new HourlyWeather(instant, 20, 5, 10, 20, 0, 0.0, 25000, 0, 2.0);
        };
        JvmScoringOpportunitySearchEngine engine = new JvmScoringOpportunitySearchEngine(
                new PreviewEvaluator(),
                provider);

        OpportunitySearchResponse response = engine.search(
                prague(),
                new OpportunitySearchRequest("prague-cz", "2026-06-29", 7, 12.0, 100),
                notBefore);

        assertTrue(response.opportunities().stream()
                .noneMatch(opportunity -> Instant.parse(opportunity.suggestedAt()).isBefore(notBefore)));
        OpportunitySearchResponse.Opportunity ongoing = response.opportunities().stream()
                .filter(opportunity -> opportunity.startsAt().equals("2026-06-28T22:00:00Z"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected the ongoing local-day window to be retained."));
        assertFalse(Instant.parse(ongoing.suggestedAt()).isBefore(notBefore));
        assertEquals(0, ongoing.weather().weatherCode());
        assertEquals("clear to mostly clear", ongoing.weather().summary());
        assertEquals(22, ongoing.components().weatherFit());
        assertTrue(ongoing.reason().contains("clear to mostly clear and 0 percent precipitation risk"));
    }

    @Test
    void translatesDirectPrototypeValidationFailuresToInvalidRequest() {
        JvmScoringOpportunitySearchEngine engine = engineWithUnusedWeather();

        InvalidOpportunitySearchRequestException exception = assertThrows(
                InvalidOpportunitySearchRequestException.class,
                () -> engine.search(new OpportunitySearchRequest("prague-cz", "2026-06-29", 0, 90.0, 5)));

        assertEquals("forecastHorizonDays must be between 1 and 30.", exception.getMessage());
        assertNotNull(exception.getCause());
    }

    @Test
    void treatsResolvedPrototypeValidationFailuresAsInternalInvariants() {
        JvmScoringOpportunitySearchEngine engine = engineWithUnusedWeather();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> engine.search(amsterdam(), new OpportunitySearchRequest("amsterdam-nl", "2026-06-29", 0, 90.0, 5)));

        assertEquals("Resolved opportunity scoring request was invalid.", exception.getMessage());
        assertNotNull(exception.getCause());
    }

    private static ResolvedLocation prague() {
        return new ResolvedLocation(
                "prague-cz",
                new ProviderLocationId(LocationProvider.OPEN_METEO, "3067696"),
                "Prague, Czechia",
                50.08804,
                14.42076,
                202,
                ZoneId.of("Europe/Prague"),
                "CZ");
    }

    private static ResolvedLocation amsterdam() {
        return new ResolvedLocation(
                "amsterdam-nl",
                new ProviderLocationId(LocationProvider.OPEN_METEO, "2759794"),
                "Amsterdam, North Holland, Netherlands",
                52.37403,
                4.88969,
                13,
                ZoneId.of("Europe/Amsterdam"),
                "NL");
    }

    private static JvmScoringOpportunitySearchEngine engineWithPartlyCloudyWeather() {
        return new JvmScoringOpportunitySearchEngine(new PreviewEvaluator(), (location, startsAt, endsAt, days) -> {
            HourlyWeather weather = toHourlyWeather(startsAt, WeatherFixture.PRAGUE_PARTLY_CLOUDY);
            return instant -> weather;
        });
    }

    private static JvmScoringOpportunitySearchEngine engineWithUnusedWeather() {
        return new JvmScoringOpportunitySearchEngine(new PreviewEvaluator(), (location, startsAt, endsAt, days) -> {
            throw new AssertionError("Weather provider should not be called by this test.");
        });
    }

    private static HourlyWeather toHourlyWeather(Instant startsAt, WeatherFixture weather) {
        return new HourlyWeather(
                startsAt,
                weather.cloudCoverPercent(),
                weather.lowCloudCoverPercent(),
                weather.midCloudCoverPercent(),
                weather.highCloudCoverPercent(),
                weather.precipitationProbabilityPercent(),
                weather.precipitationMm(),
                weather.visibilityMeters(),
                weather.weatherCode(),
                weather.forecastAgeHours());
    }
}
