package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.scoring.ScoringModel;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;

public final class WindowGenerator {
    private static final Duration BRACKET_STEP = Duration.ofHours(1);
    private static final Duration SUGGESTION_STEP = Duration.ofMinutes(5);
    private static final Duration PATH_SAMPLE_STEP = Duration.ofMinutes(30);
    private static final Duration REFINEMENT_TOLERANCE = Duration.ofSeconds(1);
    private static final Duration KIND_SAMPLE_OFFSET = Duration.ofMinutes(1);
    private static final double[] LIGHT_BUCKET_THRESHOLDS = {-12.0, -6.0, -0.833, 6.0};

    @FunctionalInterface
    public interface SampleProvider {
        MoonSample sampleAt(Instant instant);
    }

    public List<MoonWindow> findWindows(PrototypeConfig config, EphemerisSampler ephemeris) {
        return findWindows(config, instant -> ephemeris.sampleAt(config.location(), instant));
    }

    public List<MoonWindow> findWindows(PrototypeConfig config, SampleProvider samples) {
        TreeSet<Instant> passBoundaries = new TreeSet<>();
        Instant start = config.start();
        Instant end = config.end();
        passBoundaries.add(start);
        passBoundaries.add(end);
        addMoonHorizonCrossings(config, samples, passBoundaries);

        List<MoonWindow> windows = new ArrayList<>();
        Instant previous = null;
        for (Instant boundary : passBoundaries) {
            if (previous != null && boundary.isAfter(previous)) {
                addWindowsIfVisibleMoonPass(windows, config, samples, previous, boundary);
            }
            previous = boundary;
        }
        return windows;
    }

    public static Optional<MoonWindow> withSuggestedAtOrAfter(
            MoonWindow window,
            SampleProvider samples,
            Instant notBefore
    ) {
        if (!window.endsAt().isAfter(notBefore)) {
            return Optional.empty();
        }
        Instant suggestionStart = max(window.startsAt(), notBefore);
        MoonSample suggested = suggestedSample(samples, suggestionStart, window.endsAt());
        String kind = windowKind(samples, window.startsAt(), window.endsAt(), suggested);
        return Optional.of(moonWindow(
                window.location(),
                kind,
                window.passStartsAt(),
                window.passEndsAt(),
                window.startsAt(),
                suggested,
                window.endsAt(),
                samples));
    }

    private static void addMoonHorizonCrossings(
            PrototypeConfig config,
            SampleProvider samples,
            TreeSet<Instant> boundaries
    ) {
        Instant cursor = config.start();
        MoonSample previous = samples.sampleAt(cursor);
        while (cursor.isBefore(config.end())) {
            Instant nextInstant = min(cursor.plus(BRACKET_STEP), config.end());
            MoonSample next = samples.sampleAt(nextInstant);
            addThresholdCrossing(samples, boundaries, previous, next, 0.0, MoonSample::moonAltitudeDegrees);
            cursor = nextInstant;
            previous = next;
        }
    }

    private static void addThresholdCrossing(
            SampleProvider samples,
            TreeSet<Instant> boundaries,
            MoonSample previous,
            MoonSample next,
            double threshold,
            ToDoubleFunction<MoonSample> measurement
    ) {
        double previousDelta = measurement.applyAsDouble(previous) - threshold;
        double nextDelta = measurement.applyAsDouble(next) - threshold;
        if (previousDelta == 0.0) {
            boundaries.add(previous.instant());
            return;
        }
        if (nextDelta == 0.0) {
            boundaries.add(next.instant());
            return;
        }
        if ((previousDelta < 0.0 && nextDelta > 0.0) || (previousDelta > 0.0 && nextDelta < 0.0)) {
            boundaries.add(refineCrossing(samples, previous, next, threshold, measurement));
        }
    }

    private static Instant refineCrossing(
            SampleProvider samples,
            MoonSample start,
            MoonSample end,
            double threshold,
            ToDoubleFunction<MoonSample> measurement
    ) {
        Instant lower = start.instant();
        Instant upper = end.instant();
        double lowerDelta = measurement.applyAsDouble(start) - threshold;

        while (Duration.between(lower, upper).compareTo(REFINEMENT_TOLERANCE) > 0) {
            Instant middle = midpoint(lower, upper);
            double middleDelta = measurement.applyAsDouble(samples.sampleAt(middle)) - threshold;
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

    private static void addWindowsIfVisibleMoonPass(
            List<MoonWindow> windows,
            PrototypeConfig config,
            SampleProvider samples,
            Instant passStartsAt,
            Instant passEndsAt
    ) {
        Instant midpoint = midpoint(passStartsAt, passEndsAt);
        double midpointAltitude = samples.sampleAt(midpoint).moonAltitudeDegrees();
        if (midpointAltitude < 0.0) {
            return;
        }

        TreeSet<Instant> windowBoundaries = new TreeSet<>();
        windowBoundaries.add(passStartsAt);
        windowBoundaries.add(passEndsAt);
        addMaxAltitudeCrossings(config, samples, windowBoundaries, passStartsAt, passEndsAt);
        peakInstant(samples, passStartsAt, passEndsAt).ifPresent(windowBoundaries::add);

        Instant previous = null;
        for (Instant boundary : windowBoundaries) {
            if (previous != null && boundary.isAfter(previous)) {
                addWindowIfUseful(windows, config, samples, passStartsAt, passEndsAt, previous, boundary);
            }
            previous = boundary;
        }
    }

    private static void addMaxAltitudeCrossings(
            PrototypeConfig config,
            SampleProvider samples,
            TreeSet<Instant> boundaries,
            Instant startsAt,
            Instant endsAt
    ) {
        Instant cursor = startsAt;
        MoonSample previous = samples.sampleAt(cursor);
        while (cursor.isBefore(endsAt)) {
            Instant nextInstant = min(cursor.plus(BRACKET_STEP), endsAt);
            MoonSample next = samples.sampleAt(nextInstant);
            addThresholdCrossing(
                    samples,
                    boundaries,
                    previous,
                    next,
                    config.maxMoonAltitudeDegrees(),
                    MoonSample::moonAltitudeDegrees);
            cursor = nextInstant;
            previous = next;
        }
    }

    private static void addWindowIfUseful(
            List<MoonWindow> windows,
            PrototypeConfig config,
            SampleProvider samples,
            Instant passStartsAt,
            Instant passEndsAt,
            Instant startsAt,
            Instant endsAt
    ) {
        Instant midpoint = midpoint(startsAt, endsAt);
        double midpointAltitude = samples.sampleAt(midpoint).moonAltitudeDegrees();
        if (midpointAltitude < 0.0 || midpointAltitude > config.maxMoonAltitudeDegrees()) {
            return;
        }

        MoonSample suggested = suggestedSample(samples, startsAt, endsAt);
        String kind = windowKind(samples, startsAt, endsAt, suggested);
        windows.add(moonWindow(config.location(), kind, passStartsAt, passEndsAt, startsAt, suggested, endsAt, samples));
    }

    private static MoonWindow moonWindow(
            Location location,
            String kind,
            Instant passStartsAt,
            Instant passEndsAt,
            Instant startsAt,
            MoonSample suggested,
            Instant endsAt,
            SampleProvider samples
    ) {
        return new MoonWindow(
                location,
                kind,
                passStartsAt,
                passEndsAt,
                startsAt,
                samples.sampleAt(startsAt),
                suggested,
                samples.sampleAt(endsAt),
                endsAt,
                pathSamples(samples, passStartsAt, List.of(), passEndsAt),
                pathSamples(samples, startsAt, List.of(suggested.instant()), endsAt)
        );
    }

    private static Optional<Instant> peakInstant(SampleProvider samples, Instant startsAt, Instant endsAt) {
        MoonSample best = samples.sampleAt(startsAt);
        Instant cursor = startsAt.plus(SUGGESTION_STEP);
        while (cursor.isBefore(endsAt)) {
            MoonSample candidate = samples.sampleAt(cursor);
            if (candidate.moonAltitudeDegrees() > best.moonAltitudeDegrees()) {
                best = candidate;
            }
            cursor = cursor.plus(SUGGESTION_STEP);
        }
        MoonSample end = samples.sampleAt(endsAt);
        if (end.moonAltitudeDegrees() > best.moonAltitudeDegrees()) {
            best = end;
        }
        if (best.instant().equals(startsAt) || best.instant().equals(endsAt)) {
            return Optional.empty();
        }
        return Optional.of(best.instant());
    }

    private static List<MoonSample> pathSamples(
            SampleProvider samples,
            Instant startsAt,
            List<Instant> extraInstants,
            Instant endsAt
    ) {
        TreeSet<Instant> instants = new TreeSet<>();
        instants.add(startsAt);
        instants.add(endsAt);
        instants.addAll(extraInstants);

        Duration duration = Duration.between(startsAt, endsAt);
        for (int section = 1; section < 4; section++) {
            instants.add(startsAt.plus(duration.multipliedBy(section).dividedBy(4)));
        }
        addRegularPathSamples(instants, startsAt, endsAt);
        addSunAltitudeCrossings(samples, instants, startsAt, endsAt);

        return instants.stream()
                .map(samples::sampleAt)
                .toList();
    }

    private static void addRegularPathSamples(TreeSet<Instant> instants, Instant startsAt, Instant endsAt) {
        Instant cursor = startsAt.plus(PATH_SAMPLE_STEP);
        while (cursor.isBefore(endsAt)) {
            instants.add(cursor);
            cursor = cursor.plus(PATH_SAMPLE_STEP);
        }
    }

    private static void addSunAltitudeCrossings(
            SampleProvider samples,
            TreeSet<Instant> instants,
            Instant startsAt,
            Instant endsAt
    ) {
        Instant cursor = startsAt;
        MoonSample previous = samples.sampleAt(cursor);
        while (cursor.isBefore(endsAt)) {
            Instant nextInstant = min(cursor.plus(BRACKET_STEP), endsAt);
            MoonSample next = samples.sampleAt(nextInstant);
            for (double threshold : LIGHT_BUCKET_THRESHOLDS) {
                addThresholdCrossing(samples, instants, previous, next, threshold, MoonSample::sunAltitudeDegrees);
            }
            cursor = nextInstant;
            previous = next;
        }
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

    private static String windowKind(
            SampleProvider samples,
            Instant startsAt,
            Instant endsAt,
            MoonSample suggested
    ) {
        Instant startProbe = min(startsAt.plus(KIND_SAMPLE_OFFSET), midpoint(startsAt, endsAt));
        Instant endProbe = max(endsAt.minus(KIND_SAMPLE_OFFSET), midpoint(startsAt, endsAt));
        double startAltitude = samples.sampleAt(startProbe).moonAltitudeDegrees();
        double endAltitude = samples.sampleAt(endProbe).moonAltitudeDegrees();
        String trend = endAltitude >= startAltitude ? "moonrise" : "moonset";
        return trend + "_" + altitudeBand(suggested.moonAltitudeDegrees());
    }

    private static String altitudeBand(double altitude) {
        if (altitude <= 12.0) {
            return "low";
        }
        if (altitude <= 40.0) {
            return "context";
        }
        return "high_context";
    }

    private static Instant min(Instant a, Instant b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Instant max(Instant a, Instant b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
