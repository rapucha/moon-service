package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AmbientLight;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AzimuthPreference;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.DegreeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.LocalClockWindow;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimeMode;
import dev.moonservice.scoringprototype.scoring.ScoringModel;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

public final class OpportunityHardFilter {
    private static final Duration SAMPLE_STEP = Duration.ofMinutes(5);
    private static final Duration REFINEMENT_TOLERANCE = Duration.ofSeconds(1);
    private static final Duration KIND_SAMPLE_OFFSET = Duration.ofMinutes(1);
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
        List<MoonWindow> retained = new ArrayList<>();

        for (MoonWindow window : completeWindows) {
            Predicate<Instant> matches = instant ->
                    matchesAll(location, samples.sampleAt(instant), radii, preferences);
            for (Instant instant : sampleInstants(
                    window.passStartsAt(), window.startsAt(), window.endsAt(), List.of())) {
                if (!matches.test(instant)) {
                    excluded.add(new CandidateKey(window.passId(), instant));
                }
            }
            for (MatchInterval interval : matchingIntervals(
                    window.passStartsAt(), window.startsAt(), window.endsAt(), List.of(), matches)) {
                if (!interval.endsAt().isAfter(notBefore)) {
                    continue;
                }
                MoonSample suggested = bestMatchingSample(
                        window.passStartsAt(), max(interval.startsAt(), notBefore),
                        interval.endsAt(), samples, matches);
                if (suggested != null) {
                    retained.add(clippedWindow(window, interval, suggested, samples));
                }
            }
        }
        return new Result(retained, excluded.size(), masks);
    }

    static boolean matchesAll(
            Location location,
            MoonSample sample,
            LunarRadiusProvider radii,
            OpportunityPreferences preferences
    ) {
        if (preferences.altitudeDegrees() != null
                && (sample.moonAltitudeDegrees() < preferences.altitudeDegrees().minimum()
                || sample.moonAltitudeDegrees() > preferences.altitudeDegrees().maximum())) {
            return false;
        }
        if (preferences.azimuthDegrees() != null
                && !diskMatchesAzimuth(sample, radii.angularRadiusDegrees(sample.instant()),
                preferences.azimuthDegrees())) {
            return false;
        }
        if (preferences.time() != null && !matchesTime(location, sample, preferences)) {
            return false;
        }
        if (preferences.namedPhases() != null
                && !preferences.namedPhases().contains(namedPhase(sample.moonPhaseAngleDegrees()))) {
            return false;
        }
        if (preferences.brightLimbOrientationDegrees() != null) {
            Double tilt = sample.brightLimbTiltDegrees();
            if (tilt == null || preferences.brightLimbOrientationDegrees().stream()
                    .noneMatch(range -> range.contains(tilt))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesTime(
            Location location,
            MoonSample sample,
            OpportunityPreferences preferences
    ) {
        if (preferences.time().mode() == TimeMode.LIGHT_BUCKET) {
            String bucket = ScoringModel.lightBucket(sample.sunAltitudeDegrees());
            return preferences.time().lightBuckets().stream()
                    .map(AmbientLight::wireValue)
                    .anyMatch(bucket::equals);
        }
        LocalTime local = sample.instant().atZone(location.zoneId()).toLocalTime();
        LocalClockWindow window = preferences.time().localClockWindow();
        if (window.start().isBefore(window.end())) {
            return !local.isBefore(window.start()) && local.isBefore(window.end());
        }
        return !local.isBefore(window.start()) || local.isBefore(window.end());
    }

    static boolean diskMatchesAzimuth(MoonSample sample, double radiusDegrees, AzimuthPreference preference) {
        if (!Double.isFinite(radiusDegrees) || radiusDegrees <= 0.0 || radiusDegrees >= 90.0) {
            throw new IllegalArgumentException("Lunar angular radius must be finite and between 0 and 90 degrees.");
        }
        List<BearingInterval> allowed = preference.included() == null
                ? List.of(new BearingInterval(0.0, 360.0))
                : segments(preference.included());
        if (preference.excluded() != null) {
            for (BearingInterval excluded : segments(preference.excluded())) {
                allowed = subtract(allowed, excluded);
            }
        }
        if (allowed.isEmpty()) {
            return false;
        }
        List<BearingInterval> allowedSegments = allowed;

        double altitude = Math.toRadians(sample.moonAltitudeDegrees());
        double radius = Math.toRadians(radiusDegrees);
        double horizontalScale = Math.abs(Math.cos(altitude));
        if (horizontalScale < Math.sin(radius)) {
            return allowedSegments.stream().anyMatch(interval -> interval.length() > 0.0);
        }
        double halfWidth = Math.toDegrees(Math.asin(Math.sin(radius) / horizontalScale));
        List<BearingInterval> footprint = footprint(sample.moonAzimuthDegrees(), halfWidth);
        return footprint.stream().anyMatch(disk -> allowedSegments.stream().anyMatch(
                sector -> overlapLength(disk, sector) > 0.0));
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
            masks.computeIfAbsent(window.passId(), ignored -> matchingIntervals(
                    window.passStartsAt(),
                    window.passStartsAt(),
                    window.passEndsAt(),
                    boundariesByPass.get(window.passId()),
                    instant -> diskMatchesAzimuth(samples.sampleAt(instant),
                            radii.angularRadiusDegrees(instant), preference)));
        }
        return masks;
    }

    private static List<MatchInterval> matchingIntervals(
            Instant sampleAnchor,
            Instant startsAt,
            Instant endsAt,
            List<Instant> extraInstants,
            Predicate<Instant> matches
    ) {
        List<Instant> instants = sampleInstants(sampleAnchor, startsAt, endsAt, extraInstants);
        List<MatchInterval> intervals = new ArrayList<>();
        Instant previous = instants.getFirst();
        boolean previousMatches = matches.test(previous);
        Instant intervalStart = previousMatches ? previous : null;
        for (int index = 1; index < instants.size(); index++) {
            Instant next = instants.get(index);
            boolean nextMatches = matches.test(next);
            if (previousMatches != nextMatches) {
                Instant crossing = refineCrossing(previous, next, previousMatches, matches);
                if (nextMatches) {
                    intervalStart = crossing;
                } else {
                    if (crossing.isAfter(intervalStart)) {
                        intervals.add(new MatchInterval(intervalStart, crossing));
                    }
                    intervalStart = null;
                }
            }
            previous = next;
            previousMatches = nextMatches;
        }
        if (previousMatches && endsAt.isAfter(intervalStart)) {
            intervals.add(new MatchInterval(intervalStart, endsAt));
        }
        return intervals;
    }

    private static Instant refineCrossing(
            Instant start,
            Instant end,
            boolean startMatches,
            Predicate<Instant> matches
    ) {
        Instant lower = start;
        Instant upper = end;
        while (Duration.between(lower, upper).compareTo(REFINEMENT_TOLERANCE) > 0) {
            Instant middle = midpoint(lower, upper);
            if (matches.test(middle) == startMatches) {
                lower = middle;
            } else {
                upper = middle;
            }
        }
        return startMatches ? lower : upper;
    }

    private static MoonSample bestMatchingSample(
            Instant sampleAnchor,
            Instant startsAt,
            Instant endsAt,
            WindowGenerator.SampleProvider samples,
            Predicate<Instant> matches
    ) {
        return sampleInstants(sampleAnchor, startsAt, endsAt, List.of()).stream().filter(matches)
                .map(samples::sampleAt)
                .max(Comparator.comparingInt(ScoringModel::candidateFit)
                        .thenComparing(MoonSample::instant, Comparator.reverseOrder()))
                .orElse(null);
    }

    private static MoonWindow clippedWindow(
            MoonWindow source,
            MatchInterval interval,
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

    private static List<Instant> sampleInstants(
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
        Instant cursor = anchor;
        while (cursor.isBefore(endsAt)) {
            if (!cursor.isBefore(startsAt)) {
                instants.add(cursor);
            }
            cursor = cursor.plus(SAMPLE_STEP);
        }
        return List.copyOf(instants);
    }

    private static String windowKind(
            WindowGenerator.SampleProvider samples,
            MatchInterval interval,
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

    private static NamedPhase namedPhase(double angle) {
        return PHASE_BY_START_ANGLE.floorEntry(normalize(angle)).getValue();
    }

    private static List<BearingInterval> segments(DegreeRange range) {
        return range.start() < range.end()
                ? List.of(new BearingInterval(range.start(), range.end()))
                : List.of(new BearingInterval(0.0, range.end()), new BearingInterval(range.start(), 360.0));
    }

    private static List<BearingInterval> footprint(double azimuth, double halfWidth) {
        double center = normalize(azimuth);
        double start = center - halfWidth;
        double end = center + halfWidth;
        if (start < 0.0) {
            return List.of(new BearingInterval(0.0, end), new BearingInterval(start + 360.0, 360.0));
        }
        if (end > 360.0) {
            return List.of(new BearingInterval(0.0, end - 360.0), new BearingInterval(start, 360.0));
        }
        return List.of(new BearingInterval(start, end));
    }

    private static List<BearingInterval> subtract(
            List<BearingInterval> source,
            BearingInterval removed
    ) {
        List<BearingInterval> result = new ArrayList<>();
        for (BearingInterval current : source) {
            if (removed.end() <= current.start() || removed.start() >= current.end()) {
                result.add(current);
            } else {
                if (removed.start() > current.start()) {
                    result.add(new BearingInterval(current.start(), Math.min(removed.start(), current.end())));
                }
                if (removed.end() < current.end()) {
                    result.add(new BearingInterval(Math.max(removed.end(), current.start()), current.end()));
                }
            }
        }
        return result;
    }

    private static double overlapLength(BearingInterval left, BearingInterval right) {
        return Math.max(0.0, Math.min(left.end(), right.end()) - Math.max(left.start(), right.start()));
    }

    private static double normalize(double value) {
        return ((value % 360.0) + 360.0) % 360.0;
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

    private record BearingInterval(double start, double end) {
        double length() {
            return end - start;
        }
    }
}
