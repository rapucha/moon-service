package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AzimuthPreference;
import dev.moonservice.scoringprototype.scoring.ScoringModel;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

public final class OpportunityHardFilter {
    private static final Duration KIND_SAMPLE_OFFSET = Duration.ofMinutes(1);

    @FunctionalInterface
    public interface LunarRadiusProvider {
        double angularRadiusDegrees(Instant instant);
    }

    public record MatchInterval(Instant startsAt, Instant endsAt) {
        public MatchInterval {
            Objects.requireNonNull(startsAt, "startsAt");
            Objects.requireNonNull(endsAt, "endsAt");
            if (!endsAt.isAfter(startsAt)) {
                throw new IllegalArgumentException("endsAt must be after startsAt.");
            }
        }
    }

    public record Result(
            List<MoonWindow> windows,
            int excludedSampleCount,
            Map<String, List<MatchInterval>> azimuthMatchIntervals
    ) {
        public Result {
            windows = List.copyOf(windows);
            Map<String, List<MatchInterval>> copied = new LinkedHashMap<>();
            azimuthMatchIntervals.forEach((passId, intervals) -> copied.put(passId, List.copyOf(intervals)));
            azimuthMatchIntervals = Map.copyOf(copied);
        }
    }

    public Result filter(
            Location location,
            List<MoonWindow> completeWindows,
            WindowGenerator.SampleProvider sampleProvider,
            LunarRadiusProvider radiusProvider,
            OpportunityPreferences preferences,
            Instant notBefore
    ) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(completeWindows, "completeWindows");
        Objects.requireNonNull(sampleProvider, "sampleProvider");
        Objects.requireNonNull(radiusProvider, "radiusProvider");
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(notBefore, "notBefore");
        if (!preferences.active()) {
            return new Result(completeWindows, 0, Map.of());
        }

        Map<Instant, MoonSample> sampleCache = new HashMap<>();
        Map<Instant, Double> radiusCache = new HashMap<>();
        WindowGenerator.SampleProvider samples =
                instant -> sampleCache.computeIfAbsent(instant, sampleProvider::sampleAt);
        LunarRadiusProvider radii =
                instant -> radiusCache.computeIfAbsent(instant, radiusProvider::angularRadiusDegrees);
        Map<String, List<MatchInterval>> masks =
                azimuthMasks(completeWindows, samples, radii, preferences.azimuthDegrees());
        Set<CandidateKey> excluded = new HashSet<>();
        List<FilteredWindowCoalescer.SourceWindow> retained = new ArrayList<>();
        Predicate<Instant> matches = instant -> Version1PreferenceMatcher.matchesAll(
                location, samples.sampleAt(instant), radii::angularRadiusDegrees, preferences);

        for (MoonWindow window : completeWindows) {
            for (Instant instant : RefinedTimeGrid.sampleInstants(
                    window.passStartsAt(), window.startsAt(), window.endsAt(), List.of())) {
                if (!matches.test(instant)) {
                    excluded.add(new CandidateKey(window.passId(), instant));
                }
            }
            for (RefinedTimeGrid.Interval interval : RefinedTimeGrid.matchingIntervals(
                    window.passStartsAt(), window.startsAt(), window.endsAt(), List.of(), matches)) {
                if (!interval.endsAt().isAfter(notBefore)) {
                    continue;
                }
                MoonSample suggested = bestMatchingSample(
                        window.passStartsAt(), max(interval.startsAt(), notBefore),
                        interval.endsAt(), samples, matches);
                if (suggested != null) {
                    retained.add(new FilteredWindowCoalescer.SourceWindow(window,
                            clippedWindow(window, interval, suggested, samples)));
                }
            }
        }
        return new Result(FilteredWindowCoalescer.coalesce(retained), excluded.size(), masks);
    }

    private static Map<String, List<MatchInterval>> azimuthMasks(
            List<MoonWindow> windows,
            WindowGenerator.SampleProvider samples,
            LunarRadiusProvider radii,
            AzimuthPreference preference
    ) {
        if (preference == null) {
            return Map.of();
        }
        Map<String, List<Instant>> boundariesByPass = new HashMap<>();
        for (MoonWindow window : windows) {
            List<Instant> boundaries = boundariesByPass.computeIfAbsent(window.passId(), ignored -> new ArrayList<>());
            boundaries.add(window.startsAt());
            boundaries.add(window.endsAt());
        }
        Map<String, List<MatchInterval>> masks = new LinkedHashMap<>();
        for (MoonWindow window : windows) {
            masks.computeIfAbsent(window.passId(), ignored -> RefinedTimeGrid.matchingIntervals(
                            window.passStartsAt(),
                            window.passStartsAt(),
                            window.passEndsAt(),
                            boundariesByPass.get(window.passId()),
                            instant -> Version1PreferenceMatcher.matchesAzimuth(
                                    samples.sampleAt(instant),
                                    radii.angularRadiusDegrees(instant),
                                    preference)).stream()
                    .map(interval -> new MatchInterval(interval.startsAt(), interval.endsAt()))
                    .toList());
        }
        return masks;
    }

    private static MoonSample bestMatchingSample(
            Instant sampleAnchor,
            Instant startsAt,
            Instant endsAt,
            WindowGenerator.SampleProvider samples,
            Predicate<Instant> matches
    ) {
        return RefinedTimeGrid.sampleInstants(sampleAnchor, startsAt, endsAt, List.of()).stream()
                .filter(matches)
                .map(samples::sampleAt)
                .max(Comparator.comparingInt(ScoringModel::candidateFit)
                        .thenComparing(MoonSample::instant, Comparator.reverseOrder()))
                .orElse(null);
    }

    private static MoonWindow clippedWindow(
            MoonWindow source,
            RefinedTimeGrid.Interval interval,
            MoonSample suggested,
            WindowGenerator.SampleProvider samples
    ) {
        TreeSet<Instant> path = new TreeSet<>();
        path.add(interval.startsAt());
        path.add(suggested.instant());
        path.add(interval.endsAt());
        source.pathSamples().stream().map(MoonSample::instant)
                .filter(instant -> !instant.isBefore(interval.startsAt()) && !instant.isAfter(interval.endsAt()))
                .forEach(path::add);
        return new MoonWindow(
                source.location(), windowKind(samples, interval, suggested),
                source.passStartsAt(), source.passEndsAt(),
                interval.startsAt(), samples.sampleAt(interval.startsAt()), suggested,
                samples.sampleAt(interval.endsAt()), interval.endsAt(), source.passPathSamples(),
                path.stream().map(samples::sampleAt).toList());
    }

    private static String windowKind(
            WindowGenerator.SampleProvider samples,
            RefinedTimeGrid.Interval interval,
            MoonSample suggested
    ) {
        Instant middle = midpoint(interval.startsAt(), interval.endsAt());
        Instant startProbe = min(interval.startsAt().plus(KIND_SAMPLE_OFFSET), middle);
        Instant endProbe = max(interval.endsAt().minus(KIND_SAMPLE_OFFSET), middle);
        String trend = samples.sampleAt(endProbe).moonAltitudeDegrees()
                >= samples.sampleAt(startProbe).moonAltitudeDegrees() ? "moonrise" : "moonset";
        double altitude = suggested.moonAltitudeDegrees();
        String band = altitude <= 12.0 ? "low" : altitude <= 40.0 ? "context" : "high_context";
        return trend + "_" + band;
    }

    private static Instant midpoint(Instant start, Instant end) {
        return start.plus(Duration.between(start, end).dividedBy(2));
    }

    private static Instant max(Instant left, Instant right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static Instant min(Instant left, Instant right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private record CandidateKey(String passId, Instant instant) {
    }
}
