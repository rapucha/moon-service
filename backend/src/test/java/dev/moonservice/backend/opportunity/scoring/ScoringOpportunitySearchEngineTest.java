package dev.moonservice.backend.opportunity.scoring;

import static dev.moonservice.backend.opportunity.search.OpportunitySearchRequest.Order.BEST_MATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.moonservice.backend.location.LocationProvider;
import dev.moonservice.backend.location.ProviderLocationId;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine;
import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine.AzimuthMatchInterval;
import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine.PreferenceSearchResult;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.backend.weather.HourlyWeather;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.scoringprototype.PreviewEvaluator;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AltitudeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AzimuthPreference;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.DegreeRange;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import dev.moonservice.scoringprototype.scoring.WeatherRanking;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

class ScoringOpportunitySearchEngineTest {
    @Test
    void assignsBestMatchToDirectFixtureRequests() {
        ObjectNode request = new ObjectMapper().createObjectNode();
        request.put("locationId", "prague-cz");
        request.put("start", "2026-06-29");
        request.put("forecastHorizonDays", 7);
        request.put("maxMoonAltitudeDegrees", 12);
        request.put("limit", 5);

        assertEquals(BEST_MATCH, OpportunitySearchRequest.fromJson(request).order());
    }

    @Test
    void preservesNullMoonOrientationFromPrototypeResponse() throws ReflectiveOperationException {
        ObjectNode moonNode = new ObjectMapper().createObjectNode();
        moonNode.put("altitudeDegrees", 5.0);
        moonNode.put("azimuthDegrees", 120.0);
        moonNode.put("illuminationPercent", 100.0);
        moonNode.put("phaseAngleDegrees", 180.0);
        moonNode.putNull("brightLimbTiltDegrees");
        moonNode.putNull("northPoleTiltDegrees");
        moonNode.put("phaseName", "full_moon");
        Method moonMapper = PrototypeOpportunityResponseMapper.class.getDeclaredMethod("moon", JsonNode.class);
        moonMapper.setAccessible(true);

        OpportunitySearchResponse.Moon moon = (OpportunitySearchResponse.Moon) moonMapper.invoke(null, moonNode);

        assertNull(moon.brightLimbTiltDegrees());
        assertNull(moon.northPoleTiltDegrees());
    }

    @Test
    void preservesMoonPathPointOrientationFromPrototypeResponse() throws ReflectiveOperationException {
        ObjectNode pointNode = new ObjectMapper().createObjectNode();
        pointNode.put("at", "2026-01-01T00:00:00Z");
        pointNode.put("altitudeDegrees", 5.0);
        pointNode.put("azimuthDegrees", 120.0);
        pointNode.put("moonPhaseAngleDegrees", 91.0);
        pointNode.putNull("brightLimbTiltDegrees");
        pointNode.put("northPoleTiltDegrees", 270.0);
        pointNode.put("sunAltitudeDegrees", -5.0);
        pointNode.put("sunAzimuthDegrees", 210.0);
        pointNode.put("lightBucket", "civil_twilight");
        pointNode.put("role", "path");
        Method pointMapper =
                PrototypeOpportunityResponseMapper.class.getDeclaredMethod("moonPathPoint", JsonNode.class);
        pointMapper.setAccessible(true);

        OpportunitySearchResponse.MoonPathPoint point =
                (OpportunitySearchResponse.MoonPathPoint) pointMapper.invoke(null, pointNode);

        assertEquals(91.0, point.moonPhaseAngleDegrees());
        assertNull(point.brightLimbTiltDegrees());
        assertEquals(270.0, point.northPoleTiltDegrees());
    }

    @Test
    void scoresResolvedLocationCoordinatesWithoutFixtureLocationId() {
        ScoringOpportunitySearchEngine engine = engineWithPartlyCloudyWeather();

        OpportunitySearchResponse response = searchWithoutLiveCutoff(
                engine,
                amsterdam(),
                new OpportunitySearchRequest("amsterdam-nl", "2026-06-29", 7, 90.0, 5, BEST_MATCH));

        assertEquals("ok", response.status());
        assertEquals("amsterdam-nl", response.location().id());
        assertEquals("Amsterdam, North Holland, Netherlands", response.location().displayName());
        assertEquals("Europe/Amsterdam", response.location().timezone());
        assertFalse(response.opportunities().isEmpty());
        OpportunitySearchResponse.Opportunity first = response.opportunities().getFirst();
        assertTrue(first.id().startsWith("amsterdam-nl-"));
        assertTrue(first.moonPass().id().startsWith("amsterdam-nl-pass-"));
        assertFalse(first.moonPass().startsAt().isBlank());
        assertFalse(first.moonPass().endsAt().isBlank());
        assertEquals(first.moonPass().startsAt(), first.moonPass().path().start().at());
        assertEquals(first.moonPass().endsAt(), first.moonPass().path().end().at());
        assertTrue(first.moonPass().path().samples().size() >= 5);
        assertFalse(first.moonPass().path().samples().getFirst().lightBucket().isBlank());
        assertMoonPathPointOrientation(first.moonPass().path().samples().getFirst());
        assertTrue(first.links().get("ics").startsWith("/o/amsterdam-nl-"));
        assertFalse(first.moon().phaseName().isBlank());
        assertTrue(first.moon().phaseAngleDegrees() >= 0.0);
        assertTrue(first.moon().phaseAngleDegrees() < 360.0);
        assertNotNull(first.moon().brightLimbTiltDegrees());
        assertTrue(first.moon().brightLimbTiltDegrees() >= 0.0);
        assertTrue(first.moon().brightLimbTiltDegrees() < 360.0);
        assertNotNull(first.moon().northPoleTiltDegrees());
        assertTrue(first.moon().northPoleTiltDegrees() >= 0.0);
        assertTrue(first.moon().northPoleTiltDegrees() < 360.0);
        assertEquals(first.startsAt(), first.moonPath().start().at());
        assertEquals(first.suggestedAt(), first.moonPath().suggested().at());
        assertEquals(first.endsAt(), first.moonPath().end().at());
        assertFalse(first.moonPath().suggested().lightBucket().isBlank());
        assertTrue(Double.isFinite(first.moonPath().suggested().sunAltitudeDegrees()));
        assertTrue(Double.isFinite(first.moonPath().suggested().sunAzimuthDegrees()));
        assertTrue(Double.isFinite(first.sun().azimuthDegrees()));
        assertTrue(first.moonPath().samples().size() >= 5);
        assertFalse(first.moonPath().samples().getFirst().lightBucket().isBlank());
        assertMoonPathPointOrientation(first.moonPath().suggested());
        assertTrue(response.messages().stream()
                .noneMatch(message -> message.code().equals("fixture_weather")));
        assertTrue(response.messages().stream()
                .anyMatch(message -> message.code().equals("local_horizon_not_modelled")));
    }

    private static void assertMoonPathPointOrientation(OpportunitySearchResponse.MoonPathPoint point) {
        assertTrue(point.moonPhaseAngleDegrees() >= 0.0);
        assertTrue(point.moonPhaseAngleDegrees() < 360.0);
        assertNotNull(point.brightLimbTiltDegrees());
        assertTrue(point.brightLimbTiltDegrees() >= 0.0);
        assertTrue(point.brightLimbTiltDegrees() < 360.0);
        assertNotNull(point.northPoleTiltDegrees());
        assertTrue(point.northPoleTiltDegrees() >= 0.0);
        assertTrue(point.northPoleTiltDegrees() < 360.0);
    }

    @Test
    void scoresResolvedLocationWithWeatherForecastProviderData() {
        AtomicReference<ResolvedLocation> requestedLocation = new AtomicReference<>();
        AtomicReference<Instant> requestedStartsAt = new AtomicReference<>();
        AtomicReference<Instant> requestedEndsAt = new AtomicReference<>();
        WeatherForecastProvider provider = (location, startsAt, endsAt) -> {
            requestedLocation.set(location);
            requestedStartsAt.set(startsAt);
            requestedEndsAt.set(endsAt);
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
        ScoringOpportunitySearchEngine engine = new ScoringOpportunitySearchEngine(
                new PreviewEvaluator(),
                provider);

        OpportunitySearchResponse response = searchWithoutLiveCutoff(
                engine,
                amsterdam(),
                new OpportunitySearchRequest("amsterdam-nl", "2026-06-29", 7, 90.0, 5, BEST_MATCH));

        OpportunitySearchResponse.Weather weather = response.opportunities().getFirst().weather();
        assertEquals(amsterdam(), requestedLocation.get());
        assertEquals(Instant.parse("2026-06-28T22:00:00Z"), requestedStartsAt.get());
        assertEquals(Instant.parse("2026-07-05T22:00:00Z"), requestedEndsAt.get());
        assertEquals(82, weather.cloudCoverMeanPercent());
        assertEquals(70, weather.lowCloudCoverMaxPercent());
        assertEquals(35, weather.precipitationProbabilityMaxPercent());
        assertEquals(0.8, weather.precipitationMm());
        assertEquals(12000, weather.visibilityMinMeters());
        assertEquals(61, weather.weatherCode());
        assertEquals("rain likely", weather.summary());
    }

    @Test
    void liveSearchKeepsOngoingMoonPassWindowAndScoresRemainingSuggestion() {
        Instant notBefore = Instant.parse("2026-06-29T01:30:00Z");
        WeatherForecastProvider provider = (location, startsAt, endsAt) -> instant -> {
            if (instant.isBefore(notBefore)) {
                return new HourlyWeather(instant, 100, 100, 100, 100, 90, 3.0, 8000, 61, 2.0);
            }
            return new HourlyWeather(instant, 20, 5, 10, 20, 0, 0.0, 25000, 0, 2.0);
        };
        ScoringOpportunitySearchEngine engine = new ScoringOpportunitySearchEngine(
                new PreviewEvaluator(),
                provider);

        OpportunitySearchResponse response = engine.search(
                prague(),
                new OpportunitySearchRequest("prague-cz", "2026-06-29", 7, 12.0, 100, BEST_MATCH),
                notBefore);

        assertTrue(response.opportunities().stream()
                .noneMatch(opportunity -> Instant.parse(opportunity.suggestedAt()).isBefore(notBefore)));
        OpportunitySearchResponse.Opportunity ongoing = response.opportunities().stream()
                .filter(opportunity -> opportunity.moonPass().startsAt().equals("2026-06-28T22:00:00Z"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected the ongoing Moon pass window to be retained."));
        assertEquals("moonset_low", ongoing.windowKind());
        assertEquals("2026-06-28T22:15:00Z", ongoing.startsAt());
        assertEquals("2026-06-29T01:40:24Z", ongoing.moonPass().endsAt());
        assertFalse(Instant.parse(ongoing.suggestedAt()).isBefore(notBefore));
        assertEquals(0, ongoing.weather().weatherCode());
        assertEquals("mostly clear", ongoing.weather().summary());
        assertEquals(22, ongoing.components().weatherFit());
        assertTrue(ongoing.reason().contains("mostly clear and 0 percent precipitation risk"));
    }

    @Test
    void preferenceFreeTypedSearchPreservesLiveAdapterResult() {
        OpportunitySearchEngine engine = engineWithPartlyCloudyWeather();
        OpportunitySearchRequest request =
                new OpportunitySearchRequest("prague-cz", "2026-06-29", 1, 12.0, 10, BEST_MATCH);
        Instant notBefore = Instant.parse("2026-06-29T00:00:00Z");

        OpportunitySearchResponse ordinary = engine.search(prague(), request, notBefore);
        PreferenceSearchResult preferenceResult =
                engine.search(prague(), request, notBefore, OpportunityPreferences.none());

        assertEquals(1, preferenceResult.appliedPreferenceVersion());
        assertTrue(preferenceResult.normalizedActiveFilters().isEmpty());
        assertEquals(0, preferenceResult.excludedSampleCount());
        assertFalse(preferenceResult.preferencesRemovedAllLiveCandidates());
        assertTrue(preferenceResult.azimuthMatchIntervals().isEmpty());
        OpportunitySearchResponse typed = preferenceResult.response();
        assertEquals(ordinary.status(), typed.status());
        assertEquals(ordinary.location(), typed.location());
        assertEquals(ordinary.forecastHorizonDays(), typed.forecastHorizonDays());
        assertEquals(ordinary.startsAt(), typed.startsAt());
        assertEquals(ordinary.endsAt(), typed.endsAt());
        assertEquals(ordinary.candidateWindowsEvaluated(), typed.candidateWindowsEvaluated());
        assertEquals(ordinary.maxMoonAltitudeDegrees(), typed.maxMoonAltitudeDegrees());
        assertEquals(ordinary.opportunities(), typed.opportunities());
        assertEquals(ordinary.rejected(), typed.rejected());
        assertEquals(ordinary.messages(), typed.messages());
    }

    @Test
    void appliesRequestWeatherRankingToOrdinaryAndActivePreferenceSearches() {
        OpportunitySearchEngine engine = engineWithPartlyCloudyWeather();
        OpportunitySearchRequest balancedRequest =
                new OpportunitySearchRequest("prague-cz", "2026-06-29", 7, 12.0, 100, BEST_MATCH);
        OpportunitySearchRequest preferClearRequest =
                balancedRequest.withWeatherRanking(WeatherRanking.PREFER_CLEAR);
        OpportunitySearchRequest ignoreWeatherRequest =
                balancedRequest.withWeatherRanking(WeatherRanking.IGNORE_WEATHER);
        Instant notBefore = Instant.parse("2026-06-29T00:00:00Z");

        OpportunitySearchResponse balanced = engine.search(prague(), balancedRequest, notBefore);
        OpportunitySearchResponse preferClear = engine.search(prague(), preferClearRequest, notBefore);
        OpportunitySearchResponse ignoreWeather = engine.search(prague(), ignoreWeatherRequest, notBefore);

        assertFalse(balanced.opportunities().isEmpty());
        assertEquals(opportunityKeys(balanced), opportunityKeys(preferClear));
        assertEquals(opportunityKeys(balanced), opportunityKeys(ignoreWeather));
        assertNotNull(balanced.opportunities().getFirst().components().weatherFit());
        assertNotNull(preferClear.opportunities().getFirst().components().weatherFit());
        assertFalse(balanced.opportunities().getFirst().components().weatherFit()
                .equals(preferClear.opportunities().getFirst().components().weatherFit()));
        assertWeatherComponentsExcluded(ignoreWeather);
        assertNull(ignoreWeather.appliedWeatherRanking());

        OpportunityPreferences activePreferences = new OpportunityPreferences(
                1,
                new AltitudeRange(10.0, 12.0),
                null,
                null,
                null,
                null);
        PreferenceSearchResult balancedPreferences = engine.search(
                prague(), balancedRequest, notBefore, activePreferences);
        PreferenceSearchResult ignoreWeatherPreferences = engine.search(
                prague(), ignoreWeatherRequest, notBefore, activePreferences);

        assertEquals(
                balancedPreferences.normalizedActiveFilters(),
                ignoreWeatherPreferences.normalizedActiveFilters());
        assertEquals(
                balancedPreferences.excludedSampleCount(),
                ignoreWeatherPreferences.excludedSampleCount());
        assertEquals(
                opportunityKeys(balancedPreferences.response()),
                opportunityKeys(ignoreWeatherPreferences.response()));
        assertFalse(ignoreWeatherPreferences.response().opportunities().isEmpty());
        assertWeatherComponentsExcluded(ignoreWeatherPreferences.response());
        assertNull(ignoreWeatherPreferences.response().appliedWeatherRanking());
    }

    @Test
    void passesActivePreferencesThroughTheTypedInterface() {
        OpportunitySearchEngine engine = engineWithPartlyCloudyWeather();
        OpportunityPreferences preferences = new OpportunityPreferences(
                1,
                new AltitudeRange(89.0, 90.0),
                null,
                null,
                null,
                null);

        PreferenceSearchResult result = engine.search(
                prague(),
                new OpportunitySearchRequest("prague-cz", "2026-06-29", 1, 12.0, 10, BEST_MATCH),
                Instant.parse("2026-06-28T22:00:00Z"),
                preferences);

        assertEquals(1, result.appliedPreferenceVersion());
        assertEquals(preferences.normalizedFilters(), result.normalizedActiveFilters());
        assertTrue(result.excludedSampleCount() > 0);
        assertTrue(result.preferencesRemovedAllLiveCandidates());
        assertTrue(result.response().opportunities().isEmpty());
        assertTrue(result.azimuthMatchIntervals().isEmpty());
        assertEquals(
                "no_opportunities_match_preferences",
                OpportunitySearchResponse.withPreferences(result, List.of(), 0).emptyReason().code());
    }

    @Test
    void doesNotAttributeOrdinaryVisibilityRejectionToPreferences() {
        OpportunitySearchEngine engine = engineWithPartlyCloudyWeather();
        OpportunityPreferences preferences = new OpportunityPreferences(
                1,
                new AltitudeRange(0.5, 90.0),
                null,
                null,
                null,
                null);

        PreferenceSearchResult result = engine.search(
                prague(),
                new OpportunitySearchRequest("prague-cz", "2026-07-14", 1, 90.0, 100, BEST_MATCH),
                Instant.parse("2026-07-14T00:00:00Z"),
                preferences);

        assertTrue(result.excludedSampleCount() > 0);
        assertFalse(result.preferencesRemovedAllLiveCandidates());
        assertTrue(result.response().opportunities().isEmpty());
        assertFalse(result.response().rejected().isEmpty());
        assertNull(OpportunitySearchResponse.withPreferences(result, List.of(), 0).emptyReason());
    }

    @Test
    void doesNotAttributeExpiredWindowsToPreferences() {
        OpportunitySearchEngine engine = engineWithPartlyCloudyWeather();
        OpportunityPreferences preferences = new OpportunityPreferences(
                1,
                new AltitudeRange(89.0, 90.0),
                null,
                null,
                null,
                null);

        PreferenceSearchResult result = engine.search(
                prague(),
                new OpportunitySearchRequest("prague-cz", "2026-06-29", 1, 12.0, 10, BEST_MATCH),
                Instant.parse("2026-06-30T00:00:00Z"),
                preferences);

        assertTrue(result.excludedSampleCount() > 0);
        assertFalse(result.preferencesRemovedAllLiveCandidates());
        assertTrue(result.response().opportunities().isEmpty());
        assertTrue(result.response().rejected().isEmpty());
        assertNull(OpportunitySearchResponse.withPreferences(result, List.of(), 0).emptyReason());
    }

    @Test
    void typedAzimuthIntervalsRejectZeroDurationTangency() {
        Instant boundary = Instant.parse("2026-06-29T00:00:00Z");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new AzimuthMatchInterval(boundary, boundary));

        assertEquals("endsAt must be after startsAt.", exception.getMessage());
    }

    @Test
    void preservesCompleteTypedAzimuthMaskForReturnedPass() {
        OpportunitySearchEngine engine = engineWithPartlyCloudyWeather();
        OpportunityPreferences preferences = new OpportunityPreferences(
                1,
                null,
                new AzimuthPreference(new DegreeRange(10.0, 350.0), null),
                null,
                null,
                null);

        PreferenceSearchResult result = engine.search(
                prague(),
                new OpportunitySearchRequest("prague-cz", "2026-06-29", 1, 12.0, 1, BEST_MATCH),
                Instant.parse("2026-06-28T22:00:00Z"),
                preferences);

        assertEquals(preferences.normalizedFilters(), result.normalizedActiveFilters());
        OpportunitySearchResponse.MoonPass pass =
                result.response().opportunities().getFirst().moonPass();
        assertEquals(Set.of(pass.id()), result.azimuthMatchIntervals().keySet());
        List<AzimuthMatchInterval> intervals = result.azimuthMatchIntervals().get(pass.id());
        assertEquals(1, intervals.size());
        assertEquals(Instant.parse(pass.startsAt()), intervals.getFirst().startsAt());
        assertEquals(Instant.parse(pass.endsAt()), intervals.getFirst().endsAt());
    }

    @Test
    void rejectsNearConjunctionThinCrescentFalsePositiveForPragueAndAbuDhabi() {
        ScoringOpportunitySearchEngine engine = engineWithPartlyCloudyWeather();

        assertNearConjunctionRejected(searchWithoutLiveCutoff(
                engine,
                prague(),
                new OpportunitySearchRequest("prague-cz", "2026-07-14", 1, 90.0, 100, BEST_MATCH)));
        assertNearConjunctionRejected(searchWithoutLiveCutoff(
                engine,
                abuDhabi(),
                new OpportunitySearchRequest("abu-dhabi-ae", "2026-07-14", 1, 90.0, 100, BEST_MATCH)));
    }

    @Test
    void translatesDirectPrototypeValidationFailuresToInvalidRequest() {
        ScoringOpportunitySearchEngine engine = engineWithUnusedWeather();

        InvalidOpportunitySearchRequestException exception = assertThrows(
                InvalidOpportunitySearchRequestException.class,
                () -> engine.search(new OpportunitySearchRequest(
                        "prague-cz", "2026-06-29", 0, 90.0, 5, BEST_MATCH)));

        assertEquals("forecastHorizonDays must be between 1 and 30.", exception.getMessage());
        assertNotNull(exception.getCause());
    }

    @Test
    void treatsResolvedPrototypeValidationFailuresAsInternalInvariants() {
        ScoringOpportunitySearchEngine engine = engineWithUnusedWeather();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> searchWithoutLiveCutoff(
                        engine,
                        amsterdam(),
                        new OpportunitySearchRequest(
                                "amsterdam-nl", "2026-06-29", 0, 90.0, 5, BEST_MATCH)));

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

    private static OpportunitySearchResponse searchWithoutLiveCutoff(
            ScoringOpportunitySearchEngine engine,
            ResolvedLocation location,
            OpportunitySearchRequest request
    ) {
        return engine.search(location, request, Instant.MIN);
    }

    private static ResolvedLocation abuDhabi() {
        return new ResolvedLocation(
                "abu-dhabi-ae",
                new ProviderLocationId(LocationProvider.OPEN_METEO, "292968"),
                "Abu Dhabi, United Arab Emirates",
                24.4539,
                54.3773,
                27,
                ZoneId.of("Asia/Dubai"),
                "AE");
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

    private static ScoringOpportunitySearchEngine engineWithPartlyCloudyWeather() {
        return new ScoringOpportunitySearchEngine(new PreviewEvaluator(), (location, startsAt, endsAt) -> {
            HourlyWeather weather = toHourlyWeather(startsAt, WeatherFixture.PRAGUE_PARTLY_CLOUDY);
            return instant -> weather;
        });
    }

    private static ScoringOpportunitySearchEngine engineWithUnusedWeather() {
        return new ScoringOpportunitySearchEngine(new PreviewEvaluator(), (location, startsAt, endsAt) -> {
            throw new AssertionError("Weather provider should not be called by this test.");
        });
    }

    private static void assertNearConjunctionRejected(OpportunitySearchResponse response) {
        assertEquals("ok", response.status());
        assertTrue(response.opportunities().isEmpty());
        assertFalse(response.rejected().isEmpty());
        assertTrue(response.rejected().stream()
                .allMatch(window -> window.reasonCode().equals(ScoringModel.THIN_CRESCENT_NEAR_CONJUNCTION)));
        assertTrue(response.rejected().stream()
                .allMatch(window -> window.moonSunSeparationDegrees()
                        < ScoringModel.NEAR_CONJUNCTION_MIN_SEPARATION_DEGREES));
        assertTrue(response.rejected().stream()
                .allMatch(window -> window.moonIlluminationPercent()
                        < ScoringModel.NEAR_CONJUNCTION_MAX_ILLUMINATION_PERCENT));
    }

    private static List<String> opportunityKeys(OpportunitySearchResponse response) {
        return response.opportunities().stream()
                .map(opportunity -> opportunity.id() + "@" + opportunity.suggestedAt())
                .toList();
    }

    private static void assertWeatherComponentsExcluded(OpportunitySearchResponse response) {
        for (OpportunitySearchResponse.Opportunity opportunity : response.opportunities()) {
            assertNull(opportunity.components().weatherFit());
            assertNull(opportunity.components().forecastConfidence());
            assertNotNull(opportunity.weather());
            assertNotNull(opportunity.scoreBasis());
            assertTrue(opportunity.scoreBasis().componentPoints() >= 0);
            assertEquals(70, opportunity.scoreBasis().componentMaximum());
            assertEquals(
                    List.of("weatherFit", "forecastConfidence"),
                    opportunity.scoreBasis().excludedComponents());
        }
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
