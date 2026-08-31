package dev.moonservice.scoringprototype.window;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Predicate;

public final class RefinedTimeGrid {
    private static final Duration SAMPLE_STEP = Duration.ofMinutes(5);
    private static final Duration REFINEMENT_TOLERANCE = Duration.ofSeconds(1);

    private RefinedTimeGrid() {
    }

    public record Interval(Instant startsAt, Instant endsAt) {
        public Interval {
            Objects.requireNonNull(startsAt, "startsAt");
            Objects.requireNonNull(endsAt, "endsAt");
            if (!endsAt.isAfter(startsAt)) {
                throw new IllegalArgumentException("endsAt must be after startsAt.");
            }
        }
    }

    public static List<Instant> sampleInstants(
            Instant anchor,
            Instant startsAt,
            Instant endsAt,
            List<Instant> extraInstants
    ) {
        TreeSet<Instant> instants = new TreeSet<>();
        instants.add(startsAt);
        instants.add(endsAt);
        extraInstants.stream()
                .filter(instant -> !instant.isBefore(startsAt) && !instant.isAfter(endsAt))
                .forEach(instants::add);
        for (Instant cursor = anchor; cursor.isBefore(endsAt); cursor = cursor.plus(SAMPLE_STEP)) {
            if (!cursor.isBefore(startsAt)) {
                instants.add(cursor);
            }
        }
        return List.copyOf(instants);
    }

    public static List<Instant> transitionInstants(
            List<Instant> instants,
            Predicate<Instant> matches
    ) {
        List<Instant> transitions = new ArrayList<>();
        Instant previous = instants.getFirst();
        boolean previousMatches = matches.test(previous);
        for (int index = 1; index < instants.size(); index++) {
            Instant next = instants.get(index);
            boolean nextMatches = matches.test(next);
            if (previousMatches != nextMatches) {
                transitions.add(refineTransition(previous, next, previousMatches, matches));
            }
            previous = next;
            previousMatches = nextMatches;
        }
        return List.copyOf(transitions);
    }

    public static List<Interval> matchingIntervals(
            Instant anchor,
            Instant startsAt,
            Instant endsAt,
            List<Instant> extraInstants,
            Predicate<Instant> matches
    ) {
        List<Instant> instants = sampleInstants(anchor, startsAt, endsAt, extraInstants);
        List<Interval> intervals = new ArrayList<>();
        Instant previous = instants.getFirst();
        boolean previousMatches = matches.test(previous);
        Instant intervalStart = previousMatches ? previous : null;
        for (int index = 1; index < instants.size(); index++) {
            Instant next = instants.get(index);
            boolean nextMatches = matches.test(next);
            if (previousMatches != nextMatches) {
                Instant crossing = refineTransition(previous, next, previousMatches, matches);
                if (nextMatches) {
                    intervalStart = crossing;
                } else {
                    if (crossing.isAfter(intervalStart)) {
                        intervals.add(new Interval(intervalStart, crossing));
                    }
                    intervalStart = null;
                }
            }
            previous = next;
            previousMatches = nextMatches;
        }
        if (previousMatches && endsAt.isAfter(intervalStart)) {
            intervals.add(new Interval(intervalStart, endsAt));
        }
        return List.copyOf(intervals);
    }

    static Instant nextSample(Instant current, Instant inclusiveEnd) {
        Instant next = current.plus(SAMPLE_STEP);
        return next.isBefore(inclusiveEnd) ? next : inclusiveEnd;
    }

    static Instant refineTransition(
            Instant start,
            Instant end,
            boolean startMatches,
            Predicate<Instant> matches
    ) {
        Instant lower = start;
        Instant upper = end;
        while (Duration.between(lower, upper).compareTo(REFINEMENT_TOLERANCE) > 0) {
            Instant middle = lower.plus(Duration.between(lower, upper).dividedBy(2));
            if (matches.test(middle) == startMatches) {
                lower = middle;
            } else {
                upper = middle;
            }
        }
        return startMatches ? lower : upper;
    }
}
