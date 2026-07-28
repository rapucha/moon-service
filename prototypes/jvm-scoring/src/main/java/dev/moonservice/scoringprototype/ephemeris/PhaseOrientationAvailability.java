package dev.moonservice.scoringprototype.ephemeris;

import dev.moonservice.scoringprototype.input.OpportunityPreferences.DegreeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import dev.moonservice.scoringprototype.window.WindowGenerator;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

public final class PhaseOrientationAvailability {
    private static final int LOOK_AHEAD_DAYS = 200;
    private static final Duration LOOK_AHEAD = Duration.ofDays(LOOK_AHEAD_DAYS);
    private static final Duration SAMPLE_STEP = Duration.ofMinutes(5);
    private static final Duration REFINEMENT_TOLERANCE = Duration.ofSeconds(1);
    // Keys are inclusive. NEW_MOON appears at both ends because its sector crosses 0°.
    private static final NavigableMap<Double, NamedPhase> PHASE_BY_START_ANGLE =
            Collections.unmodifiableNavigableMap(new TreeMap<>(Map.ofEntries(
                    Map.entry(0.0, NamedPhase.NEW_MOON),
                    Map.entry(22.5, NamedPhase.WAXING_CRESCENT),
                    Map.entry(67.5, NamedPhase.FIRST_QUARTER),
                    Map.entry(112.5, NamedPhase.WAXING_GIBBOUS),
                    Map.entry(157.5, NamedPhase.FULL_MOON),
                    Map.entry(202.5, NamedPhase.WANING_GIBBOUS),
                    Map.entry(247.5, NamedPhase.LAST_QUARTER),
                    Map.entry(292.5, NamedPhase.WANING_CRESCENT),
                    Map.entry(337.5, NamedPhase.NEW_MOON)
            )));

    public Result find(
            Instant notBefore,
            Set<NamedPhase> namedPhases,
            DegreeRange brightLimbRange,
            WindowGenerator.SampleProvider samples
    ) {
        Objects.requireNonNull(notBefore, "notBefore");
        Objects.requireNonNull(namedPhases, "namedPhases");
        Objects.requireNonNull(brightLimbRange, "brightLimbRange");
        Objects.requireNonNull(samples, "samples");
        if (namedPhases.isEmpty()) {
            throw new IllegalArgumentException("namedPhases must not be empty.");
        }

        Instant endsAt = notBefore.plus(LOOK_AHEAD);
        Predicate<Instant> matches = instant ->
                matches(samples.sampleAt(instant), namedPhases, brightLimbRange);
        Instant previous = notBefore;
        if (matches.test(previous)) {
            return new Result(LOOK_AHEAD_DAYS, previous);
        }
        while (previous.isBefore(endsAt)) {
            Instant next = min(previous.plus(SAMPLE_STEP), endsAt);
            if (matches.test(next)) {
                return new Result(
                        LOOK_AHEAD_DAYS,
                        refineFalseToTrue(previous, next, matches));
            }
            previous = next;
        }
        return new Result(LOOK_AHEAD_DAYS, null);
    }

    private static boolean matches(
            MoonSample sample,
            Set<NamedPhase> namedPhases,
            DegreeRange brightLimbRange
    ) {
        Double tilt = sample.brightLimbTiltDegrees();
        return sample.moonAltitudeDegrees() >= 0.0
                && namedPhases.contains(namedPhase(sample.moonPhaseAngleDegrees()))
                && tilt != null
                && brightLimbRange.contains(tilt);
    }

    private static NamedPhase namedPhase(double angle) {
        return PHASE_BY_START_ANGLE.floorEntry(normalize(angle)).getValue();
    }

    private static Instant refineFalseToTrue(
            Instant start,
            Instant end,
            Predicate<Instant> matches
    ) {
        Instant lower = start;
        Instant upper = end;
        while (Duration.between(lower, upper).compareTo(REFINEMENT_TOLERANCE) > 0) {
            Instant middle = lower.plus(Duration.between(lower, upper).dividedBy(2));
            if (matches.test(middle)) {
                upper = middle;
            } else {
                lower = middle;
            }
        }
        return upper;
    }

    private static Instant min(Instant left, Instant right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static double normalize(double value) {
        return ((value % 360.0) + 360.0) % 360.0;
    }

    public record Result(int lookAheadDays, Instant nextMatchAt) {
    }
}
