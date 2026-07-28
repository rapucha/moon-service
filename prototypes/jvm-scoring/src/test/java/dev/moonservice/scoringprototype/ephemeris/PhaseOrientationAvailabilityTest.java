package dev.moonservice.scoringprototype.ephemeris;

import dev.moonservice.scoringprototype.input.OpportunityPreferences.DegreeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseOrientationAvailabilityTest {
    private static final Instant START = Instant.parse("2026-06-29T00:00:00Z");
    private static final Instant END = START.plus(Duration.ofDays(200));
    private static final DegreeRange ZERO_RANGE = new DegreeRange(350.0, 10.0);
    private static final PhaseOrientationAvailability FINDER = new PhaseOrientationAvailability();

    @Test
    void includesMatchingInitialInstantAndCircularOrientationBoundary() {
        PhaseOrientationAvailability.Result result = FINDER.find(
                START,
                Set.of(NamedPhase.FULL_MOON),
                ZERO_RANGE,
                instant -> sample(instant, 1.0, 157.5));

        assertEquals(START, result.nextMatchAt());
    }

    @Test
    void refinesTheFirstFalseToTrueCrossingToOneSecond() {
        Instant crossing = START.plusSeconds(133);

        PhaseOrientationAvailability.Result result = FINDER.find(
                START,
                Set.of(NamedPhase.FULL_MOON),
                ZERO_RANGE,
                instant -> sample(instant, 1.0, instant.isBefore(crossing) ? 0.0 : 180.0));

        assertTrue(!result.nextMatchAt().isBefore(crossing));
        assertTrue(Duration.between(crossing, result.nextMatchAt()).compareTo(Duration.ofSeconds(1)) <= 0);
    }

    @Test
    void includesAMatchExactlyAtTheLookAheadBoundary() {
        PhaseOrientationAvailability.Result result = FINDER.find(
                START,
                Set.of(NamedPhase.FULL_MOON),
                ZERO_RANGE,
                instant -> sample(instant, 1.0, instant.equals(END) ? 180.0 : 0.0));

        assertEquals(END, result.nextMatchAt());
    }

    @Test
    void reportsNotFoundAfterSamplingBothInclusiveEndpoints() {
        AtomicInteger samples = new AtomicInteger();

        PhaseOrientationAvailability.Result result = FINDER.find(
                START,
                Set.of(NamedPhase.FULL_MOON),
                ZERO_RANGE,
                instant -> {
                    samples.incrementAndGet();
                    return sample(instant, -1.0, 180.0);
                });

        assertNull(result.nextMatchAt());
        assertEquals(57_601, samples.get());
    }

    @Test
    void acceptsTheEarliestOfMultipleSelectedPhases() {
        Instant firstQuarter = START.plusSeconds(127);
        Instant fullMoon = START.plus(Duration.ofMinutes(10));

        PhaseOrientationAvailability.Result result = FINDER.find(
                START,
                Set.of(NamedPhase.FIRST_QUARTER, NamedPhase.FULL_MOON),
                ZERO_RANGE,
                instant -> sample(
                        instant,
                        1.0,
                        instant.isBefore(firstQuarter) ? 45.0
                                : instant.isBefore(fullMoon) ? 90.0 : 180.0));

        assertTrue(!result.nextMatchAt().isBefore(firstQuarter));
        assertTrue(result.nextMatchAt().isBefore(firstQuarter.plusSeconds(1)));
    }

    @Test
    void ignoresACombinedPhaseAndOrientationMatchBelowTheHorizon() {
        Instant moonrise = START.plusSeconds(141);

        PhaseOrientationAvailability.Result result = FINDER.find(
                START,
                Set.of(NamedPhase.FULL_MOON),
                ZERO_RANGE,
                instant -> sample(instant, instant.isBefore(moonrise) ? -1.0 : 1.0, 180.0));

        assertTrue(!result.nextMatchAt().isBefore(moonrise));
        assertTrue(result.nextMatchAt().isBefore(moonrise.plusSeconds(1)));
    }

    @Test
    void rejectsAnUndefinedBrightLimbDirection() {
        PhaseOrientationAvailability.Result result = FINDER.find(
                START,
                Set.of(NamedPhase.FULL_MOON),
                ZERO_RANGE,
                instant -> undefinedTiltSample(instant));

        assertNull(result.nextMatchAt());
    }

    @ParameterizedTest
    @MethodSource("phaseBoundaries")
    void mapsBothSidesOfEveryNamedPhaseBoundary(
            double angle,
            NamedPhase boundaryPhase,
            NamedPhase precedingPhase
    ) {
        PhaseOrientationAvailability.Result atBoundary = FINDER.find(
                START,
                Set.of(boundaryPhase),
                ZERO_RANGE,
                instant -> sample(instant, 1.0, angle));
        PhaseOrientationAvailability.Result belowBoundary = FINDER.find(
                START,
                Set.of(precedingPhase),
                ZERO_RANGE,
                instant -> sample(instant, 1.0, angle - 0.001));

        assertEquals(START, atBoundary.nextMatchAt());
        assertEquals(START, belowBoundary.nextMatchAt());
    }

    private static Stream<Arguments> phaseBoundaries() {
        return Stream.of(
                Arguments.of(0.0, NamedPhase.NEW_MOON, NamedPhase.NEW_MOON),
                Arguments.of(22.5, NamedPhase.WAXING_CRESCENT, NamedPhase.NEW_MOON),
                Arguments.of(67.5, NamedPhase.FIRST_QUARTER, NamedPhase.WAXING_CRESCENT),
                Arguments.of(112.5, NamedPhase.WAXING_GIBBOUS, NamedPhase.FIRST_QUARTER),
                Arguments.of(157.5, NamedPhase.FULL_MOON, NamedPhase.WAXING_GIBBOUS),
                Arguments.of(202.5, NamedPhase.WANING_GIBBOUS, NamedPhase.FULL_MOON),
                Arguments.of(247.5, NamedPhase.LAST_QUARTER, NamedPhase.WANING_GIBBOUS),
                Arguments.of(292.5, NamedPhase.WANING_CRESCENT, NamedPhase.LAST_QUARTER),
                Arguments.of(337.5, NamedPhase.NEW_MOON, NamedPhase.WANING_CRESCENT));
    }

    private static MoonSample sample(Instant instant, double altitude, double phase) {
        return new MoonSample(instant, altitude, 0.0, 50.0, phase, 0.0, altitude + 10.0, 0.0);
    }

    private static MoonSample undefinedTiltSample(Instant instant) {
        return new MoonSample(instant, 1.0, 0.0, 50.0, 180.0, 0.0, 1.0, 0.0);
    }
}
