package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.UsageException;
import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.fixture.Locations;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AmbientLight;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AltitudeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AzimuthPreference;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.DegreeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.LocalClockWindow;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimeMode;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimePreference;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import dev.moonservice.scoringprototype.service.OpportunityService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpportunityHardFilterTest {
    private static final OpportunityHardFilter FILTER = new OpportunityHardFilter();
    private static final Location PRAGUE = Locations.PRAGUE;
    private static final Instant BASE = Instant.parse("2026-06-29T20:00:00Z");

    @Test
    void validatesAndNormalizesTheTypedVersionOneModel() {
        OpportunityPreferences preferences = new OpportunityPreferences(
                1,
                new AltitudeRange(0.0, 90.0),
                new AzimuthPreference(new DegreeRange(350.0, 20.0), new DegreeRange(355.0, 5.0)),
                new TimePreference(TimeMode.LOCAL_CLOCK,
                        new LocalClockWindow(LocalTime.of(23, 0), LocalTime.of(1, 0)), Set.of()),
                Set.of(NamedPhase.FULL_MOON),
                List.of(new DegreeRange(330.0, 20.0)));

        assertTrue(preferences.active());
        assertEquals(Set.of("altitudeDegrees", "azimuthDegrees", "time", "namedPhases",
                "brightLimbOrientationDegrees"), preferences.normalizedFilters().keySet());
        assertEquals(Map.of(
                        "mode", "local_clock",
                        "window", Map.of("start", "23:00", "end", "01:00")),
                preferences.normalizedFilters().get("time"));
        assertFalse(OpportunityPreferences.none().active());

        assertThrows(UsageException.class, () -> new OpportunityPreferences(2, null, null, null, null, null));
        assertThrows(UsageException.class, () -> new AltitudeRange(-0.1, 2.0));
        assertThrows(UsageException.class, () -> new AltitudeRange(5.0, 4.0));
        assertThrows(UsageException.class, () -> new DegreeRange(0.0, 360.0));
        assertThrows(UsageException.class, () -> new DegreeRange(1.0, 1.0));
        assertThrows(UsageException.class,
                () -> new AzimuthPreference(new DegreeRange(20.0, 40.0), new DegreeRange(35.0, 50.0)));
        assertThrows(UsageException.class, () -> new LocalClockWindow(null, LocalTime.NOON));
        assertThrows(UsageException.class, () -> new TimePreference(null, null, Set.of()));
        assertThrows(UsageException.class,
                () -> new TimePreference(TimeMode.LOCAL_CLOCK, null, Set.of()));
        assertThrows(UsageException.class, () -> new TimePreference(
                TimeMode.LOCAL_CLOCK, new LocalClockWindow(LocalTime.NOON, LocalTime.MIDNIGHT),
                Set.of(AmbientLight.NIGHT)));
        assertThrows(UsageException.class, () -> new TimePreference(
                TimeMode.LIGHT_BUCKET, new LocalClockWindow(LocalTime.NOON, LocalTime.MIDNIGHT),
                Set.of(AmbientLight.NIGHT)));
        assertThrows(UsageException.class,
                () -> new OpportunityPreferences(1, null, null, null, Set.of(), null));
        assertThrows(UsageException.class, () -> new OpportunityPreferences(
                1, null, null, null, null,
                List.of(new DegreeRange(0, 1), new DegreeRange(1, 2), new DegreeRange(2, 3),
                        new DegreeRange(3, 4), new DegreeRange(4, 5), new DegreeRange(5, 6),
                        new DegreeRange(6, 7), new DegreeRange(7, 8), new DegreeRange(8, 9))));
    }

    @Test
    void altitudeEndpointsAreInclusiveAndCrossingsAreRefined() {
        Function<Instant, MoonSample> samples = instant -> {
            double minutes = Duration.between(BASE, instant).toSeconds() / 60.0;
            return sample(instant, minutes / 6.0, 100.0, 180.0, -8.0, 200.0);
        };
        MoonWindow complete = window(BASE, BASE.plus(Duration.ofHours(1)), samples);
        OpportunityHardFilter.Result result = filter(
                List.of(complete), samples, altitude(2.0, 6.0), BASE, 0.25);

        assertEquals(1, result.windows().size());
        MoonWindow retained = result.windows().getFirst();
        assertNear(BASE.plus(Duration.ofMinutes(12)), retained.startsAt());
        assertNear(BASE.plus(Duration.ofMinutes(36)), retained.endsAt());
        assertTrue(retained.start().moonAltitudeDegrees() >= 2.0);
        assertTrue(retained.end().moonAltitudeDegrees() <= 6.0);
        assertTrue(matches(altitude(2.0, 6.0),
                sample(BASE, 2.0, 100.0, 180.0, -8.0, 200.0), 0.25));
        assertTrue(matches(altitude(2.0, 6.0),
                sample(BASE, 6.0, 100.0, 180.0, -8.0, 200.0), 0.25));
        assertEquals(8, result.excludedSampleCount());
        assertTrue(matches(altitude(2.0, 6.0), retained.suggested(), 0.25));
    }

    @Test
    void excludedCandidateCountIsDistinctAcrossFiltersAndOmitsRefinementSamples() {
        Function<Instant, MoonSample> samples = constantSamples(5.0, 100.0, 180.0, -8.0, 200.0);
        MoonWindow complete = window(BASE, BASE.plus(Duration.ofMinutes(10)), samples);
        OpportunityPreferences preferences = new OpportunityPreferences(
                1,
                new AltitudeRange(80.0, 90.0),
                null,
                null,
                Set.of(NamedPhase.NEW_MOON),
                null);

        OpportunityHardFilter.Result result =
                filter(List.of(complete), samples, preferences, BASE, 0.25);

        assertTrue(result.windows().isEmpty());
        assertEquals(3, result.excludedSampleCount());
    }

    @Test
    void oneClockWindowClipsACompleteWindow() {
        Function<Instant, MoonSample> samples = constantSamples(5.0, 100.0, 180.0, -8.0, 200.0);
        MoonWindow complete = window(BASE, BASE.plus(Duration.ofHours(4)), samples);
        OpportunityPreferences preferences =
                clock(new LocalClockWindow(LocalTime.of(22, 32), LocalTime.of(22, 47)));

        OpportunityHardFilter.Result result = filter(
                List.of(complete), samples, preferences, BASE, 0.25);

        assertEquals(1, result.windows().size());
        assertNear(BASE.plus(Duration.ofMinutes(32)), result.windows().get(0).startsAt());
        assertNear(BASE.plus(Duration.ofMinutes(47)), result.windows().get(0).endsAt());
        assertEquals(complete.passId(), result.windows().get(0).passId());
    }

    @Test
    void blockedProvisionalSuggestionDoesNotHideALaterMatch() {
        Function<Instant, MoonSample> samples = instant -> {
            double minutes = minutesAfterBase(instant);
            double altitude = minutes <= 60.0 ? 6.0 : 6.0 + (minutes - 60.0) / 2.0;
            return sample(instant, altitude, 100.0, 180.0, 1.0, 200.0);
        };
        MoonWindow complete = window(BASE, BASE.plus(Duration.ofHours(2)), samples);
        assertFalse(matches(clock(new LocalClockWindow(LocalTime.of(23, 30), LocalTime.MIDNIGHT)),
                complete.suggested(), 0.25));

        OpportunityHardFilter.Result result = filter(
                List.of(complete), samples,
                clock(new LocalClockWindow(LocalTime.of(23, 30), LocalTime.MIDNIGHT)), BASE, 0.25);

        assertEquals(1, result.windows().size());
        assertEquals(BASE.plus(Duration.ofMinutes(90)), result.windows().getFirst().startsAt());
        assertTrue(matches(clock(new LocalClockWindow(LocalTime.of(23, 30), LocalTime.MIDNIGHT)),
                result.windows().getFirst().suggested(), 0.25));
        assertTrue(ScoringModel.candidateFit(complete.suggested())
                > ScoringModel.candidateFit(result.windows().getFirst().suggested()));
    }

    @Test
    void localClockUsesLocationTimezoneAndHandlesDstGapAndOverlap() {
        OpportunityPreferences gap = clock(
                new LocalClockWindow(LocalTime.of(2, 15), LocalTime.of(2, 45)));
        Instant gapStart = Instant.parse("2026-03-29T00:00:00Z");
        Function<Instant, MoonSample> samples = constantSamples(5.0, 100.0, 180.0, -8.0, 200.0);
        assertTrue(filter(List.of(window(gapStart, gapStart.plus(Duration.ofHours(4)), samples)),
                samples, gap, gapStart, 0.25).windows().isEmpty());

        Instant overlapStart = Instant.parse("2026-10-25T00:00:00Z");
        OpportunityHardFilter.Result overlap = filter(
                List.of(window(overlapStart, overlapStart.plus(Duration.ofHours(3)), samples)),
                samples, gap, overlapStart, 0.25);
        assertEquals(2, overlap.windows().size());
        assertEquals(Instant.parse("2026-10-25T00:15:00Z"), overlap.windows().get(0).startsAt());
        assertEquals(Instant.parse("2026-10-25T01:15:00Z"), overlap.windows().get(1).startsAt());

        OpportunityHardFilter.Result midnight = filter(
                List.of(window(BASE, BASE.plus(Duration.ofHours(4)), samples)), samples,
                clock(new LocalClockWindow(LocalTime.of(23, 30), LocalTime.of(0, 30))), BASE, 0.25);
        assertEquals(1, midnight.windows().size());
        assertEquals(BASE.plus(Duration.ofMinutes(90)), midnight.windows().getFirst().startsAt());
    }

    @Test
    void allAmbientLightBucketsAreAlternativesToClockMode() {
        Map<AmbientLight, Double> altitudes = Map.of(
                AmbientLight.DAYLIGHT, 10.0,
                AmbientLight.GOLDEN_HOUR, 1.0,
                AmbientLight.CIVIL_TWILIGHT, -2.0,
                AmbientLight.NAUTICAL_TWILIGHT, -8.0,
                AmbientLight.NIGHT, -20.0);
        for (Map.Entry<AmbientLight, Double> entry : altitudes.entrySet()) {
            Function<Instant, MoonSample> samples =
                    constantSamples(5.0, 100.0, 180.0, entry.getValue(), 200.0);
            OpportunityPreferences preference = light(entry.getKey());
            assertEquals(1, filter(List.of(window(BASE, BASE.plus(Duration.ofMinutes(10)), samples)),
                    samples, preference, BASE, 0.25).windows().size());
        }
    }

    @Test
    void namedPhaseRangesUseEveryInclusiveSectorStart() {
        Map<Double, NamedPhase> phasesByStartAngle = Map.ofEntries(
                Map.entry(0.0, NamedPhase.NEW_MOON),
                Map.entry(22.5, NamedPhase.WAXING_CRESCENT),
                Map.entry(67.5, NamedPhase.FIRST_QUARTER),
                Map.entry(112.5, NamedPhase.WAXING_GIBBOUS),
                Map.entry(157.5, NamedPhase.FULL_MOON),
                Map.entry(202.5, NamedPhase.WANING_GIBBOUS),
                Map.entry(247.5, NamedPhase.LAST_QUARTER),
                Map.entry(292.5, NamedPhase.WANING_CRESCENT),
                Map.entry(337.5, NamedPhase.NEW_MOON));
        for (Map.Entry<Double, NamedPhase> entry : phasesByStartAngle.entrySet()) {
            Function<Instant, MoonSample> samples =
                    constantSamples(5.0, 100.0, entry.getKey(), -8.0, 200.0);
            OpportunityPreferences preference = phases(entry.getValue());
            assertEquals(1, filter(List.of(window(BASE, BASE.plus(Duration.ofMinutes(10)), samples)),
                    samples, preference, BASE, 0.25).windows().size());
            assertTrue(entry.getValue() == NamedPhase.NEW_MOON
                    || filter(List.of(window(BASE, BASE.plus(Duration.ofMinutes(10)), samples)),
                    samples, phases(NamedPhase.NEW_MOON), BASE, 0.25).windows().isEmpty());
        }
    }

    @Test
    void brightLimbRangesSupportOrdinaryAndNorthCrossingUnions() {
        Function<Instant, MoonSample> ninetyDegrees =
                constantSamples(5.0, 0.0, 135.0, 0.0, 90.0);
        assertEquals(1, filter(
                List.of(window(BASE, BASE.plus(Duration.ofMinutes(10)), ninetyDegrees)),
                ninetyDegrees, bright(new DegreeRange(80, 100)), BASE, 0.25).windows().size());

        Function<Instant, MoonSample> nearNorth =
                constantSamples(5.0, 0.0, 135.0, 10.0, 0.0);
        assertEquals(1, filter(List.of(window(BASE, BASE.plus(Duration.ofMinutes(10)), nearNorth)),
                nearNorth, bright(new DegreeRange(350, 20)), BASE, 0.25).windows().size());

        Function<Instant, MoonSample> missing =
                constantSamples(5.0, 0.0, 135.0, 5.0, 0.0);
        assertTrue(filter(List.of(window(BASE, BASE.plus(Duration.ofMinutes(10)), missing)),
                missing, bright(new DegreeRange(350, 20)), BASE, 0.25).windows().isEmpty());
    }

    @Test
    void azimuthUsesDiskAreaIncludedExcludedAndNorthCrossingGeometry() {
        MoonSample ordinary = sample(BASE, 0.0, 100.0, 180.0, -8.0, 200.0);
        assertTrue(disk(ordinary, 1.0, new DegreeRange(99.5, 101), null));
        assertTrue(disk(ordinary, 1.0, new DegreeRange(100.5, 100.5 + 1.0e-12), null));
        assertTrue(disk(sample(BASE, 0.0, 0.0, 180.0, -8.0, 200.0),
                1.0, new DegreeRange(350, 10), null));
        assertFalse(disk(ordinary, 1.0, new DegreeRange(101, 110), null));
        assertFalse(disk(ordinary, 1.0, null, new DegreeRange(99, 101)));
        assertTrue(disk(ordinary, 1.0, null, new DegreeRange(99.75, 100.25)));
        assertTrue(disk(ordinary, 1.0, new DegreeRange(99, 101), new DegreeRange(99, 100)));
        assertFalse(disk(sample(BASE, 0.0, 98.0, 180.0, -8.0, 200.0),
                1.5, new DegreeRange(99, 110), new DegreeRange(99, 100)));
    }

    @Test
    void azimuthProjectsRadiusAtAltitudeAndSpansCompassAtZenith() {
        MoonSample horizon = sample(BASE, 0.0, 100.0, 180.0, -8.0, 200.0);
        MoonSample high = sample(BASE, 60.0, 100.0, 180.0, -8.0, 200.0);
        MoonSample zenith = sample(BASE, 90.0, 100.0, 180.0, -8.0, 200.0);
        DegreeRange offset = new DegreeRange(101.5, 110.0);
        assertFalse(disk(horizon, 1.0, offset, null));
        assertTrue(disk(high, 1.0, offset, null));
        assertTrue(disk(zenith, 1.0, new DegreeRange(250, 251), null));
        DegreeRange removedWrappedSector = new DegreeRange(279, 0);
        assertFalse(disk(zenith, 1.0, removedWrappedSector, removedWrappedSector));
    }

    @Test
    void phaseLightBrightLimbAndAzimuthCrossingsAreRefined() {
        Function<Instant, MoonSample> phaseSamples = instant -> sample(
                instant,
                5.0,
                100.0,
                150.0 + 2.0 * minutesAfterBase(instant),
                -8.0,
                200.0);
        OpportunityHardFilter.Result phase = filter(
                List.of(window(BASE, BASE.plus(Duration.ofMinutes(30)), phaseSamples)),
                phaseSamples,
                phases(NamedPhase.FULL_MOON),
                BASE,
                0.25);
        assertNear(atMinutes(3.75), phase.windows().getFirst().startsAt());
        assertNear(atMinutes(26.25), phase.windows().getFirst().endsAt());

        Function<Instant, MoonSample> lightSamples = instant -> sample(
                instant,
                5.0,
                100.0,
                180.0,
                -8.0 + minutesAfterBase(instant),
                200.0);
        OpportunityHardFilter.Result light = filter(
                List.of(window(BASE, BASE.plus(Duration.ofMinutes(10)), lightSamples)),
                lightSamples,
                light(AmbientLight.CIVIL_TWILIGHT),
                BASE,
                0.25);
        assertNear(atMinutes(2.0), light.windows().getFirst().startsAt());
        assertNear(atMinutes(7.167), light.windows().getFirst().endsAt());

        Function<Instant, MoonSample> brightSamples = instant -> sample(
                instant,
                0.0,
                0.0,
                135.0,
                10.0,
                2.0 * minutesAfterBase(instant));
        OpportunityHardFilter.Result bright = filter(
                List.of(window(BASE, BASE.plus(Duration.ofMinutes(12)), brightSamples)),
                brightSamples,
                bright(new DegreeRange(30.0, 60.0)),
                BASE,
                0.25);
        assertNear(atMinutes(sunAzimuthAtTilt(30.0) / 2.0), bright.windows().getFirst().startsAt());
        assertNear(atMinutes(sunAzimuthAtTilt(60.0) / 2.0), bright.windows().getFirst().endsAt());

        Function<Instant, MoonSample> azimuthSamples = instant -> sample(
                instant,
                0.0,
                95.0 + 2.0 * minutesAfterBase(instant),
                180.0,
                -8.0,
                200.0);
        MoonWindow azimuthWindow = window(BASE, BASE.plus(Duration.ofMinutes(15)), azimuthSamples);
        OpportunityHardFilter.Result azimuth = filter(
                List.of(azimuthWindow),
                azimuthSamples,
                azimuth(new DegreeRange(100.0, 110.0), null),
                BASE,
                1.0);
        OpportunityHardFilter.MatchInterval mask =
                azimuth.azimuthMatchIntervals().get(azimuthWindow.passId()).getFirst();
        assertNear(atMinutes(2.0), azimuth.windows().getFirst().startsAt());
        assertNear(atMinutes(8.0), azimuth.windows().getFirst().endsAt());
        assertEquals(azimuth.windows().getFirst().startsAt(), mask.startsAt());
        assertEquals(azimuth.windows().getFirst().endsAt(), mask.endsAt());
    }

    @Test
    void azimuthMaskUsesWholePassAndIsIndependentOfOtherFilters() {
        Function<Instant, MoonSample> samples = instant -> {
            double minutes = Duration.between(BASE, instant).toMinutes();
            return sample(instant, 5.0, 90.0 + minutes, 180.0, -8.0, 200.0);
        };
        MoonWindow first = window(BASE, BASE.plus(Duration.ofHours(2)), BASE,
                BASE.plus(Duration.ofHours(1)), samples);
        MoonWindow second = window(BASE, BASE.plus(Duration.ofHours(2)), BASE.plus(Duration.ofHours(1)),
                BASE.plus(Duration.ofHours(2)), samples);
        OpportunityPreferences preference = new OpportunityPreferences(
                1, new AltitudeRange(80, 90),
                new AzimuthPreference(new DegreeRange(100, 120), null), null, null, null);

        OpportunityHardFilter.Result result =
                filter(List.of(first, second), samples, preference, BASE, 0.25);

        assertTrue(result.windows().isEmpty());
        assertEquals(1, result.azimuthMatchIntervals().size());
        OpportunityHardFilter.MatchInterval mask = result.azimuthMatchIntervals().get(first.passId()).getFirst();
        assertTrue(!mask.startsAt().isBefore(first.passStartsAt()));
        assertTrue(!mask.endsAt().isAfter(first.passEndsAt()));
        assertTrue(mask.startsAt().isBefore(first.endsAt()));
    }

    @Test
    void azimuthMaskAndHardFilterShareNaturalBoundarySamplesAndCrossings() {
        Function<Instant, MoonSample> samples = instant -> sample(
                instant,
                5.0,
                100.0 + 10.0 * Math.abs(minutesAfterBase(instant) - 2.0),
                180.0,
                -8.0,
                200.0);
        Instant passEnd = BASE.plus(Duration.ofMinutes(10));
        Instant naturalBoundary = BASE.plus(Duration.ofMinutes(2));
        MoonWindow first = window(BASE, passEnd, BASE, naturalBoundary, samples);
        MoonWindow second = window(BASE, passEnd, naturalBoundary, passEnd, samples);

        OpportunityHardFilter.Result result = filter(
                List.of(first, second),
                samples,
                azimuth(new DegreeRange(99.5, 100.5), null),
                BASE,
                0.25);

        assertEquals(1, result.windows().size());
        assertEquals(3, result.excludedSampleCount());
        OpportunityHardFilter.MatchInterval mask =
                result.azimuthMatchIntervals().get(first.passId()).getFirst();
        assertEquals(result.windows().getFirst().startsAt(), mask.startsAt());
        assertEquals(result.windows().getFirst().endsAt(), mask.endsAt());
        assertTrue(mask.startsAt().isBefore(naturalBoundary));
        assertTrue(mask.endsAt().isAfter(naturalBoundary));
    }

    @Test
    void liveNotBeforeDropsEndedIntervalsWithoutShorteningPassMask() {
        Function<Instant, MoonSample> samples = instant -> {
            long minutes = Duration.between(BASE, instant).toMinutes();
            double azimuth = minutes < 30 || (minutes >= 60 && minutes < 90) ? 100.0 : 200.0;
            return sample(instant, 5.0, azimuth, 180.0, -8.0, 200.0);
        };
        MoonWindow complete = window(BASE, BASE.plus(Duration.ofHours(2)), samples);
        OpportunityPreferences preference = azimuth(new DegreeRange(95, 105), null);
        OpportunityHardFilter.Result result = filter(
                List.of(complete), samples, preference, BASE.plus(Duration.ofMinutes(45)), 0.25);

        assertEquals(1, result.windows().size());
        assertTrue(!result.windows().getFirst().suggested().instant()
                .isBefore(BASE.plus(Duration.ofMinutes(45))));
        assertEquals(2, result.azimuthMatchIntervals().get(complete.passId()).size());
    }

    @Test
    void samplerReturnsActualTopocentricAngularRadiusAsPrimitiveDegrees() throws Exception {
        EphemerisSampler sampler = new EphemerisSampler();
        Location sydney = new Location(
                "sydney-au", "real_location", "test:sydney-au", "Sydney, Australia",
                -33.8688, 151.2093, 58.0, "Australia/Sydney", "AU");
        Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        double pragueRadius = sampler.topocentricLunarAngularRadiusDegrees(PRAGUE, instant);
        double sydneyRadius = sampler.topocentricLunarAngularRadiusDegrees(sydney, instant);

        assertEquals(double.class, EphemerisSampler.class
                .getMethod("topocentricLunarAngularRadiusDegrees", Location.class, Instant.class)
                .getReturnType());
        assertTrue(pragueRadius > 0.23 && pragueRadius < 0.30);
        assertTrue(sydneyRadius > 0.23 && sydneyRadius < 0.30);
        assertNotEquals(pragueRadius, sydneyRadius);
    }

    @Test
    void preferenceFreeEvaluationPreservesCurrentResultExactly() {
        PrototypeConfig config = new PrototypeConfig(
                PRAGUE, LocalDate.parse("2026-06-29"), 2, 12.0, 5);
        OpportunityService service = new OpportunityService();

        assertEquals(service.evaluate(config),
                evaluateWithDefaultWeather(
                        service, config, OpportunityPreferences.none(), config.start()).result());
    }

    @Test
    void activeEvaluationRescoresTheFinalMatchingSuggestion() {
        PrototypeConfig config = new PrototypeConfig(
                PRAGUE, LocalDate.parse("2026-06-29"), 2, 12.0, 5);
        OpportunityService service = new OpportunityService();
        Set<Instant> originalSuggestions = service.evaluate(config).opportunities().stream()
                .map(item -> item.window().suggested().instant())
                .collect(java.util.stream.Collectors.toSet());
        List<Instant> weatherSuggestions = new ArrayList<>();
        OpportunityService.PreferenceEvaluation evaluation = service.evaluate(
                config,
                window -> {
                    weatherSuggestions.add(window.suggested().instant());
                    return WeatherFixture.PRAGUE_PARTLY_CLOUDY;
                },
                altitude(10, 12),
                config.start());

        assertFalse(evaluation.result().opportunities().isEmpty());
        assertTrue(evaluation.result().opportunities().stream()
                .anyMatch(item -> !originalSuggestions.contains(item.window().suggested().instant())));
        assertTrue(evaluation.result().opportunities().stream().allMatch(item ->
                item.components().equals(ScoringModel.scoreWindow(item.window(), item.weather()))));
        assertTrue(evaluation.result().opportunities().stream()
                .allMatch(item -> weatherSuggestions.contains(item.window().suggested().instant())));
        assertEquals(Map.of("minimum", 10.0, "maximum", 12.0),
                evaluation.normalizedActiveFilters().get("altitudeDegrees"));
    }

    @Test
    void serviceRanksRetainedIntervalsGloballyThenLimitsAndKeepsPassScopedMasks() {
        OpportunityPreferences preferences = new OpportunityPreferences(
                1,
                null,
                new AzimuthPreference(new DegreeRange(10.0, 350.0), null),
                new TimePreference(TimeMode.LOCAL_CLOCK,
                        new LocalClockWindow(LocalTime.MIDNIGHT, LocalTime.of(4, 0)), Set.of()),
                null,
                null);
        OpportunityService service = new OpportunityService();
        PrototypeConfig fullConfig = new PrototypeConfig(
                PRAGUE, LocalDate.parse("2026-06-29"), 7, 12.0, 100);
        OpportunityService.PreferenceEvaluation full =
                evaluateWithDefaultWeather(service, fullConfig, preferences, fullConfig.start());
        assertTrue(full.result().opportunities().size() > 1);
        String highestRankedPassId = full.result().opportunities().getFirst().window().passId();

        assertTrue(full.azimuthMatchIntervals().containsKey(highestRankedPassId));

        PrototypeConfig limitedConfig = new PrototypeConfig(
                PRAGUE, LocalDate.parse("2026-06-29"), 7, 12.0, 1);
        OpportunityService.PreferenceEvaluation limited =
                evaluateWithDefaultWeather(service, limitedConfig, preferences, limitedConfig.start());
        String returnedPassId = limited.result().opportunities().getFirst().window().passId();

        assertEquals(highestRankedPassId, returnedPassId);
        assertEquals(full.result().opportunities().getFirst().window(),
                limited.result().opportunities().getFirst().window());
        assertEquals(Set.of(returnedPassId), limited.azimuthMatchIntervals().keySet());
        assertEquals(full.azimuthMatchIntervals().get(returnedPassId),
                limited.azimuthMatchIntervals().get(returnedPassId));
    }

    private static OpportunityHardFilter.Result filter(
            List<MoonWindow> windows,
            Function<Instant, MoonSample> samples,
            OpportunityPreferences preferences,
            Instant notBefore,
            double radius
    ) {
        return FILTER.filter(PRAGUE, windows, samples::apply, ignored -> radius, preferences, notBefore);
    }

    private static OpportunityService.PreferenceEvaluation evaluateWithDefaultWeather(
            OpportunityService service,
            PrototypeConfig config,
            OpportunityPreferences preferences,
            Instant notBefore
    ) {
        return service.evaluate(
                config, ignored -> WeatherFixture.PRAGUE_PARTLY_CLOUDY, preferences, notBefore);
    }

    private static boolean matches(OpportunityPreferences preferences, MoonSample sample, double radius) {
        Function<Instant, MoonSample> samples = instant -> new MoonSample(
                instant,
                sample.moonAltitudeDegrees(),
                sample.moonAzimuthDegrees(),
                sample.moonIlluminationPercent(),
                sample.moonPhaseAngleDegrees(),
                sample.northPoleTiltDegrees(),
                sample.sunAltitudeDegrees(),
                sample.sunAzimuthDegrees());
        MoonWindow window = window(sample.instant(), sample.instant().plusSeconds(1), samples);
        return !filter(List.of(window), samples, preferences, sample.instant(), radius).windows().isEmpty();
    }

    private static boolean disk(
            MoonSample sample,
            double radius,
            DegreeRange included,
            DegreeRange excluded
    ) {
        return Version1PreferenceMatcher.matchesAzimuth(
                sample, radius, new AzimuthPreference(included, excluded));
    }

    private static OpportunityPreferences altitude(double minimum, double maximum) {
        return new OpportunityPreferences(1, new AltitudeRange(minimum, maximum), null, null, null, null);
    }

    private static OpportunityPreferences azimuth(DegreeRange included, DegreeRange excluded) {
        return new OpportunityPreferences(
                1, null, new AzimuthPreference(included, excluded), null, null, null);
    }

    private static OpportunityPreferences clock(LocalClockWindow window) {
        return new OpportunityPreferences(1, null, null,
                new TimePreference(TimeMode.LOCAL_CLOCK, window, Set.of()), null, null);
    }

    private static OpportunityPreferences light(AmbientLight... buckets) {
        return new OpportunityPreferences(1, null, null,
                new TimePreference(TimeMode.LIGHT_BUCKET, null, EnumSet.copyOf(List.of(buckets))),
                null, null);
    }

    private static OpportunityPreferences phases(NamedPhase... phases) {
        return new OpportunityPreferences(1, null, null, null, EnumSet.copyOf(List.of(phases)), null);
    }

    private static OpportunityPreferences bright(DegreeRange... ranges) {
        return new OpportunityPreferences(1, null, null, null, null, List.of(ranges));
    }

    private static double minutesAfterBase(Instant instant) {
        return Duration.between(BASE, instant).toNanos() / 60_000_000_000.0;
    }

    private static Instant atMinutes(double minutes) {
        return BASE.plusNanos(Math.round(minutes * 60_000_000_000.0));
    }

    private static double sunAzimuthAtTilt(double tiltDegrees) {
        double sine = Math.tan(Math.toRadians(tiltDegrees)) * Math.tan(Math.toRadians(10.0));
        return Math.toDegrees(Math.asin(sine));
    }

    private static void assertNear(Instant expected, Instant actual) {
        assertTrue(Duration.between(expected, actual).abs().compareTo(Duration.ofSeconds(1)) <= 0,
                () -> "Expected " + actual + " within one second of " + expected);
    }

    private static Function<Instant, MoonSample> constantSamples(
            double altitude,
            double azimuth,
            double phase,
            double sunAltitude,
            double sunAzimuth
    ) {
        return instant -> sample(instant, altitude, azimuth, phase, sunAltitude, sunAzimuth);
    }

    private static MoonSample sample(
            Instant instant,
            double altitude,
            double azimuth,
            double phase,
            double sunAltitude,
            double sunAzimuth
    ) {
        return new MoonSample(instant, altitude, azimuth, 90.0, phase, null, sunAltitude, sunAzimuth);
    }

    private static MoonWindow window(
            Instant startsAt,
            Instant endsAt,
            Function<Instant, MoonSample> samples
    ) {
        return window(startsAt, endsAt, startsAt, endsAt, samples);
    }

    private static MoonWindow window(
            Instant passStartsAt,
            Instant passEndsAt,
            Instant startsAt,
            Instant endsAt,
            Function<Instant, MoonSample> samples
    ) {
        Instant suggestedAt = startsAt.plus(Duration.between(startsAt, endsAt).dividedBy(2));
        return new MoonWindow(
                PRAGUE, "moonrise_low", passStartsAt, passEndsAt, startsAt,
                samples.apply(startsAt), samples.apply(suggestedAt), samples.apply(endsAt), endsAt,
                List.of(samples.apply(passStartsAt), samples.apply(passEndsAt)),
                List.of(samples.apply(startsAt), samples.apply(suggestedAt), samples.apply(endsAt)));
    }
}
