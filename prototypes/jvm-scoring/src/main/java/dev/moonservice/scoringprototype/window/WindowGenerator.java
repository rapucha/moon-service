package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.scoring.ScoringModel;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

public final class WindowGenerator {
    private static final Duration BRACKET_STEP = Duration.ofHours(1);
    private static final Duration SUGGESTION_STEP = Duration.ofMinutes(5);
    private static final Duration REFINEMENT_TOLERANCE = Duration.ofSeconds(1);
    private static final Duration KIND_SAMPLE_OFFSET = Duration.ofMinutes(1);

    @FunctionalInterface
    public interface SampleProvider {
        MoonSample sampleAt(Instant instant);
    }

    public List<MoonWindow> findWindows(PrototypeConfig config, EphemerisSampler ephemeris) {
        return findWindows(config, instant -> ephemeris.sampleAt(config.location(), instant));
    }

    public List<MoonWindow> findWindows(PrototypeConfig config, SampleProvider samples) {
        TreeSet<Instant> boundaries = new TreeSet<>();
        Instant start = config.start();
        Instant end = config.end();
        boundaries.add(start);
        boundaries.add(end);
        addLocalDayBoundaries(config, boundaries);
        addAltitudeCrossings(config, samples, boundaries);

        List<MoonWindow> windows = new ArrayList<>();
        Instant previous = null;
        for (Instant boundary : boundaries) {
            if (previous != null && boundary.isAfter(previous)) {
                addWindowIfLowMoon(windows, config, samples, previous, boundary);
            }
            previous = boundary;
        }
        return windows;
    }

    private static void addLocalDayBoundaries(PrototypeConfig config, TreeSet<Instant> boundaries) {
        for (int day = 1; day < config.days(); day++) {
            LocalDate date = config.startDate().plusDays(day);
            boundaries.add(date.atStartOfDay(config.location().zoneId()).toInstant());
        }
    }

    private static void addAltitudeCrossings(
            PrototypeConfig config,
            SampleProvider samples,
            TreeSet<Instant> boundaries
    ) {
        Instant cursor = config.start();
        MoonSample previous = samples.sampleAt(cursor);
        while (cursor.isBefore(config.end())) {
            Instant nextInstant = min(cursor.plus(BRACKET_STEP), config.end());
            MoonSample next = samples.sampleAt(nextInstant);
            addThresholdCrossing(samples, boundaries, previous, next, 0.0);
            addThresholdCrossing(samples, boundaries, previous, next, config.maxMoonAltitudeDegrees());
            cursor = nextInstant;
            previous = next;
        }
    }

    private static void addThresholdCrossing(
            SampleProvider samples,
            TreeSet<Instant> boundaries,
            MoonSample previous,
            MoonSample next,
            double threshold
    ) {
        double previousDelta = previous.moonAltitudeDegrees() - threshold;
        double nextDelta = next.moonAltitudeDegrees() - threshold;
        if (previousDelta == 0.0) {
            boundaries.add(previous.instant());
            return;
        }
        if (nextDelta == 0.0) {
            boundaries.add(next.instant());
            return;
        }
        if ((previousDelta < 0.0 && nextDelta > 0.0) || (previousDelta > 0.0 && nextDelta < 0.0)) {
            boundaries.add(refineCrossing(samples, previous, next, threshold));
        }
    }

    private static Instant refineCrossing(
            SampleProvider samples,
            MoonSample start,
            MoonSample end,
            double threshold
    ) {
        Instant lower = start.instant();
        Instant upper = end.instant();
        double lowerDelta = start.moonAltitudeDegrees() - threshold;

        while (Duration.between(lower, upper).compareTo(REFINEMENT_TOLERANCE) > 0) {
            Instant middle = midpoint(lower, upper);
            double middleDelta = samples.sampleAt(middle).moonAltitudeDegrees() - threshold;
            if (sameSign(lowerDelta, middleDelta)) {
                lower = middle;
                lowerDelta = middleDelta;
            } else {
                upper = middle;
            }
        }
        return midpoint(lower, upper).truncatedTo(ChronoUnit.SECONDS);
    }

    private static boolean sameSign(double a, double b) {
        return (a < 0.0 && b < 0.0) || (a > 0.0 && b > 0.0);
    }

    private static Instant midpoint(Instant start, Instant end) {
        return start.plus(Duration.between(start, end).dividedBy(2));
    }

    private static void addWindowIfLowMoon(
            List<MoonWindow> windows,
            PrototypeConfig config,
            SampleProvider samples,
            Instant startsAt,
            Instant endsAt
    ) {
        Instant midpoint = midpoint(startsAt, endsAt);
        double midpointAltitude = samples.sampleAt(midpoint).moonAltitudeDegrees();
        if (midpointAltitude < 0.0 || midpointAltitude > config.maxMoonAltitudeDegrees()) {
            return;
        }

        MoonSample suggested = suggestedSample(samples, startsAt, endsAt);
        String kind = windowKind(samples, startsAt, endsAt);
        windows.add(new MoonWindow(config.location(), kind, startsAt, suggested, endsAt));
    }

    private static MoonSample suggestedSample(SampleProvider samples, Instant startsAt, Instant endsAt) {
        List<MoonSample> candidates = new ArrayList<>();
        Instant cursor = startsAt;
        while (cursor.isBefore(endsAt)) {
            candidates.add(samples.sampleAt(cursor));
            cursor = cursor.plus(SUGGESTION_STEP);
        }
        candidates.add(samples.sampleAt(endsAt));
        return candidates.stream()
                .max(Comparator.comparingInt(ScoringModel::candidateFit)
                        .thenComparing(MoonSample::instant, Comparator.reverseOrder()))
                .orElseThrow();
    }

    private static String windowKind(SampleProvider samples, Instant startsAt, Instant endsAt) {
        Instant startProbe = min(startsAt.plus(KIND_SAMPLE_OFFSET), midpoint(startsAt, endsAt));
        Instant endProbe = max(endsAt.minus(KIND_SAMPLE_OFFSET), midpoint(startsAt, endsAt));
        double startAltitude = samples.sampleAt(startProbe).moonAltitudeDegrees();
        double endAltitude = samples.sampleAt(endProbe).moonAltitudeDegrees();
        return endAltitude >= startAltitude ? "moonrise_low" : "moonset_low";
    }

    private static Instant min(Instant a, Instant b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Instant max(Instant a, Instant b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
