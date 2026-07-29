package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.fixture.Locations;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AltitudeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AzimuthPreference;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.DegreeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.LocalClockWindow;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimeMode;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimePreference;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreferenceImpactAnalysisTest {
    private static final Location PRAGUE = Locations.PRAGUE;
    private static final PrototypeConfig CONFIG =
            new PrototypeConfig(PRAGUE, LocalDate.parse("2026-06-29"), 1, 90.0, 100);
    private static final Instant START = CONFIG.start();
    private static final Instant LOOK_AHEAD_END =
            START.plus(Duration.ofDays(PreferenceImpactAnalysis.LOOK_AHEAD_DAYS));
    private static final PreferenceImpactAnalysis ANALYSIS = new PreferenceImpactAnalysis();

    @Test
    void countsAFilteredSourceOnceWhenClockWindowsSplitIt() {
        OpportunityPreferences preferences = new OpportunityPreferences(
                1, null, null,
                new TimePreference(
                        TimeMode.LOCAL_CLOCK,
                        List.of(
                                new LocalClockWindow(LocalTime.MIDNIGHT, LocalTime.of(1, 0)),
                                new LocalClockWindow(LocalTime.of(2, 0), LocalTime.of(3, 0))),
                        Set.of()),
                null, null);

        PreferenceImpactAnalysis.Result result = analyze(preferences, PreferenceImpactAnalysisTest::visibleSample);

        assertEquals(1, result.unfilteredOpportunityCount());
        assertEquals(1, result.filters().getFirst().matchingOpportunityCount());
        assertEquals(START, result.filters().getFirst().nextMatchAt());
    }

    @Test
    void evaluatesAllSingletonsInStableOrderWithOneSharedCoarseScan() {
        AtomicInteger samples = new AtomicInteger();
        AtomicInteger radii = new AtomicInteger();
        OpportunityPreferences preferences = new OpportunityPreferences(
                1,
                new AltitudeRange(80.0, 90.0),
                new AzimuthPreference(new DegreeRange(200.0, 210.0), null),
                new TimePreference(
                        TimeMode.LOCAL_CLOCK,
                        List.of(new LocalClockWindow(LocalTime.MIDNIGHT, LocalTime.of(1, 0))),
                        Set.of()),
                Set.of(NamedPhase.NEW_MOON),
                List.of(new DegreeRange(337.5, 22.5)));

        PreferenceImpactAnalysis.Result result = ANALYSIS.analyze(
                CONFIG,
                START,
                preferences,
                instant -> {
                    samples.incrementAndGet();
                    return undefinedTiltSample(instant);
                },
                instant -> {
                    radii.incrementAndGet();
                    return 0.25;
                });
        Map<String, PreferenceImpactAnalysis.FilterImpact> byKey = result.filters().stream()
                .collect(Collectors.toMap(
                        PreferenceImpactAnalysis.FilterImpact::filter,
                        Function.identity()));

        assertEquals(List.of(
                        "altitudeDegrees", "azimuthDegrees", "time",
                        "namedPhases", "brightLimbOrientationDegrees"),
                result.filters().stream().map(PreferenceImpactAnalysis.FilterImpact::filter).toList());
        assertEquals(1, result.unfilteredOpportunityCount());
        assertEquals(0, byKey.get("altitudeDegrees").matchingOpportunityCount());
        assertEquals(0, byKey.get("azimuthDegrees").matchingOpportunityCount());
        assertEquals(1, byKey.get("time").matchingOpportunityCount());
        assertEquals(0, byKey.get("namedPhases").matchingOpportunityCount());
        assertEquals(0, byKey.get("brightLimbOrientationDegrees").matchingOpportunityCount());
        assertEquals(START, byKey.get("time").nextMatchAt());
        assertNull(byKey.get("brightLimbOrientationDegrees").nextMatchAt());
        assertTrue(samples.get() < 58_000, "five filters must share one 200-day sample scan");
        assertTrue(radii.get() < 58_000, "azimuth radii must be cached across the analysis");
    }

    @Test
    void appliesLiveAndOrdinaryVisibilityRulesOnlyToTheBaselineCounts() {
        OpportunityPreferences altitude =
                new OpportunityPreferences(1, new AltitudeRange(0.0, 90.0), null, null, null, null);
        PreferenceImpactAnalysis.Result invisible = analyze(
                altitude,
                instant -> nearConjunctionSample(instant));
        PreferenceImpactAnalysis.Result ended = ANALYSIS.analyze(
                CONFIG,
                CONFIG.end(),
                altitude,
                PreferenceImpactAnalysisTest::visibleSample,
                instant -> 0.25);

        assertEquals(0, invisible.unfilteredOpportunityCount());
        assertEquals(0, invisible.filters().getFirst().matchingOpportunityCount());
        assertEquals(START, invisible.filters().getFirst().nextMatchAt());
        assertEquals(0, ended.unfilteredOpportunityCount());
        assertEquals(0, ended.filters().getFirst().matchingOpportunityCount());
        assertEquals(CONFIG.end(), ended.filters().getFirst().nextMatchAt());
    }

    @Test
    void refinesTheFirstMatchAndIncludesBothLookAheadEndpoints() {
        Instant crossing = START.plusSeconds(133);
        OpportunityPreferences altitude =
                new OpportunityPreferences(1, new AltitudeRange(6.0, 90.0), null, null, null, null);
        PreferenceImpactAnalysis.Result refined = analyze(
                altitude,
                instant -> sample(instant, instant.isBefore(crossing) ? 5.0 : 6.0, 100.0, 180.0, -8.0, 200.0));
        PreferenceImpactAnalysis.Result finalEndpoint = analyze(
                new OpportunityPreferences(1, new AltitudeRange(80.0, 90.0), null, null, null, null),
                instant -> sample(
                        instant, instant.equals(LOOK_AHEAD_END) ? 85.0 : 5.0,
                        100.0, 180.0, -8.0, 200.0));

        Instant refinedAt = refined.filters().getFirst().nextMatchAt();
        assertTrue(!refinedAt.isBefore(crossing));
        assertTrue(Duration.between(crossing, refinedAt).compareTo(Duration.ofSeconds(1)) <= 0);
        assertEquals(LOOK_AHEAD_END, finalEndpoint.filters().getFirst().nextMatchAt());
        assertEquals(PreferenceImpactAnalysis.LOOK_AHEAD_DAYS,
                finalEndpoint.filters().getFirst().lookAheadDays());
    }

    @Test
    void inclusiveAdjacentBrightLimbSectorsCoverTheWholeCircle() {
        List<DegreeRange> sectors = List.of(
                new DegreeRange(337.5, 22.5),
                new DegreeRange(22.5, 67.5),
                new DegreeRange(67.5, 112.5),
                new DegreeRange(112.5, 157.5),
                new DegreeRange(157.5, 202.5),
                new DegreeRange(202.5, 247.5),
                new DegreeRange(247.5, 292.5),
                new DegreeRange(292.5, 337.5));
        OpportunityPreferences bright =
                new OpportunityPreferences(1, null, null, null, null, sectors);

        for (double angle = 0.0; angle < 360.0; angle += 0.5) {
            MoonSample sample = sampleAtTilt(START, angle);
            assertTrue(OpportunityHardFilter.matchesAll(PRAGUE, sample, instant -> 0.25, bright),
                    () -> "Expected coverage at " + sample.brightLimbTiltDegrees());
        }
        MoonSample sharedBoundary = sampleAtTilt(START, 22.5);
        assertTrue(sectors.get(0).contains(sharedBoundary.brightLimbTiltDegrees()));
        assertTrue(sectors.get(1).contains(sharedBoundary.brightLimbTiltDegrees()));
        assertFalse(OpportunityHardFilter.matchesAll(
                PRAGUE, undefinedTiltSample(START), instant -> 0.25, bright));
    }

    private static PreferenceImpactAnalysis.Result analyze(
            OpportunityPreferences preferences,
            Function<Instant, MoonSample> samples
    ) {
        return ANALYSIS.analyze(CONFIG, START, preferences, samples::apply, instant -> 0.25);
    }

    private static MoonSample visibleSample(Instant instant) {
        return sample(instant, 5.0, 100.0, 180.0, -8.0, 200.0);
    }

    private static MoonSample undefinedTiltSample(Instant instant) {
        return sample(instant, 5.0, 100.0, 180.0, 5.0, 100.0);
    }

    private static MoonSample nearConjunctionSample(Instant instant) {
        return new MoonSample(instant, 5.0, 100.0, 0.5, 0.0, null, 5.0, 101.0);
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

    private static MoonSample sampleAtTilt(Instant instant, double tiltDegrees) {
        double moonAltitude = Math.toRadians(5.0);
        double separation = Math.toRadians(10.0);
        double tilt = Math.toRadians(tiltDegrees);
        double east = Math.sin(separation) * Math.sin(tilt);
        double north = Math.cos(separation) * Math.cos(moonAltitude)
                - Math.sin(separation) * Math.cos(tilt) * Math.sin(moonAltitude);
        double up = Math.cos(separation) * Math.sin(moonAltitude)
                + Math.sin(separation) * Math.cos(tilt) * Math.cos(moonAltitude);
        double sunAltitude = Math.toDegrees(Math.asin(up));
        double sunAzimuth = Math.toDegrees(Math.atan2(east, north));
        if (sunAzimuth < 0.0) {
            sunAzimuth += 360.0;
        }
        return sample(instant, 5.0, 0.0, 180.0, sunAltitude, sunAzimuth);
    }
}
