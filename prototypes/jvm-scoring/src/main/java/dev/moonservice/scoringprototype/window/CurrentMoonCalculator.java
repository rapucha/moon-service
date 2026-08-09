package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CurrentMoonCalculator {
    private static final Duration SEARCH_RANGE = Duration.ofHours(26);
    private static final Duration BRACKET_STEP = Duration.ofHours(1);

    public Result calculate(Location location, Instant asOf) {
        Objects.requireNonNull(location, "location");
        EphemerisSampler ephemeris = new EphemerisSampler();
        return calculate(asOf, instant -> ephemeris.sampleAt(location, instant));
    }

    Result calculate(Instant asOf, WindowGenerator.SampleProvider samples) {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(samples, "samples");

        Map<Instant, MoonSample> cache = new HashMap<>();
        WindowGenerator.SampleProvider cachedSamples = instant -> cache.computeIfAbsent(
                instant,
                key -> Objects.requireNonNull(samples.sampleAt(key), "samples returned null"));
        MoonSample current = cachedSamples.sampleAt(asOf);
        Instant searchEndsAt = asOf.plus(SEARCH_RANGE);
        if (current.moonAltitudeDegrees() < 0.0) {
            Boundary nextRiseBoundary = findNextRise(cachedSamples, current, searchEndsAt);
            return new Result(
                    current,
                    null,
                    nextRiseBoundary,
                    nextPass(cachedSamples, nextRiseBoundary, searchEndsAt));
        }

        Instant searchStartsAt = asOf.minus(SEARCH_RANGE);
        Boundary startBoundary = findLatestRise(cachedSamples, current, searchStartsAt);
        Boundary endBoundary = findNextSet(cachedSamples, current, searchEndsAt);
        Instant representedStartsAt = startBoundary.at() == null ? searchStartsAt : startBoundary.at();
        Instant representedEndsAt = endBoundary.at() == null ? searchEndsAt : endBoundary.at();
        List<MoonSample> pathSamples = WindowGenerator.pathSamples(
                cachedSamples,
                representedStartsAt,
                List.of(asOf),
                representedEndsAt);

        return new Result(
                current,
                new ActivePass(
                        startBoundary,
                        endBoundary,
                        representedStartsAt,
                        representedEndsAt,
                        pathSamples),
                null,
                null);
    }

    private static Boundary findLatestRise(
            WindowGenerator.SampleProvider samples,
            MoonSample current,
            Instant searchStartsAt
    ) {
        MoonSample later = current;
        while (later.instant().isAfter(searchStartsAt)) {
            Instant earlierInstant = later.instant().minus(BRACKET_STEP);
            if (earlierInstant.isBefore(searchStartsAt)) {
                earlierInstant = searchStartsAt;
            }
            MoonSample earlier = samples.sampleAt(earlierInstant);
            Instant crossing = risingCrossing(samples, earlier, later);
            if (crossing != null) {
                return new Boundary(BoundaryStatus.FOUND, crossing);
            }
            later = earlier;
        }
        return new Boundary(BoundaryStatus.NOT_FOUND_WITHIN_RANGE, null);
    }

    private static Boundary findNextSet(
            WindowGenerator.SampleProvider samples,
            MoonSample current,
            Instant searchEndsAt
    ) {
        MoonSample earlier = current;
        while (earlier.instant().isBefore(searchEndsAt)) {
            Instant laterInstant = earlier.instant().plus(BRACKET_STEP);
            if (laterInstant.isAfter(searchEndsAt)) {
                laterInstant = searchEndsAt;
            }
            MoonSample later = samples.sampleAt(laterInstant);
            Instant crossing = settingCrossing(samples, earlier, later);
            if (crossing != null) {
                return new Boundary(BoundaryStatus.FOUND, crossing);
            }
            earlier = later;
        }
        return new Boundary(BoundaryStatus.NOT_FOUND_WITHIN_RANGE, null);
    }

    private static Boundary findNextRise(
            WindowGenerator.SampleProvider samples,
            MoonSample current,
            Instant searchEndsAt
    ) {
        MoonSample earlier = current;
        while (earlier.instant().isBefore(searchEndsAt)) {
            Instant laterInstant = earlier.instant().plus(BRACKET_STEP);
            if (laterInstant.isAfter(searchEndsAt)) {
                laterInstant = searchEndsAt;
            }
            MoonSample later = samples.sampleAt(laterInstant);
            Instant crossing = risingCrossing(samples, earlier, later);
            if (crossing != null) {
                return new Boundary(BoundaryStatus.FOUND, crossing);
            }
            earlier = later;
        }
        return new Boundary(BoundaryStatus.NOT_FOUND_WITHIN_RANGE, null);
    }

    private static NextPass nextPass(
            WindowGenerator.SampleProvider samples,
            Boundary startBoundary,
            Instant searchEndsAt
    ) {
        Instant startsAt = startBoundary.at();
        if (startsAt == null || !startsAt.isBefore(searchEndsAt)) {
            return null;
        }
        MoonSample start = samples.sampleAt(startsAt);
        Boundary endBoundary = findNextSet(samples, start, searchEndsAt);
        Instant representedEndsAt = endBoundary.at() == null ? searchEndsAt : endBoundary.at();
        List<MoonSample> pathSamples = WindowGenerator.pathSamples(
                samples,
                startsAt,
                List.of(),
                representedEndsAt);
        return new NextPass(startBoundary, endBoundary, startsAt, representedEndsAt, pathSamples);
    }

    private static Instant risingCrossing(
            WindowGenerator.SampleProvider samples,
            MoonSample earlier,
            MoonSample later
    ) {
        double earlierAltitude = earlier.moonAltitudeDegrees();
        double laterAltitude = later.moonAltitudeDegrees();
        if (earlierAltitude == 0.0 && laterAltitude > 0.0) {
            return earlier.instant();
        }
        if (earlierAltitude < 0.0 && laterAltitude == 0.0) {
            return later.instant();
        }
        if (earlierAltitude < 0.0 && laterAltitude > 0.0) {
            return clampRefinedCrossing(samples, earlier, later);
        }
        return null;
    }

    private static Instant settingCrossing(
            WindowGenerator.SampleProvider samples,
            MoonSample earlier,
            MoonSample later
    ) {
        double earlierAltitude = earlier.moonAltitudeDegrees();
        double laterAltitude = later.moonAltitudeDegrees();
        if (earlierAltitude > 0.0 && laterAltitude == 0.0) {
            return later.instant();
        }
        if (earlierAltitude == 0.0 && laterAltitude < 0.0) {
            return earlier.instant();
        }
        if (earlierAltitude > 0.0 && laterAltitude < 0.0) {
            return clampRefinedCrossing(samples, earlier, later);
        }
        return null;
    }

    private static Instant clampRefinedCrossing(
            WindowGenerator.SampleProvider samples,
            MoonSample earlier,
            MoonSample later
    ) {
        Instant crossing = WindowGenerator.refineCrossing(
                samples,
                earlier,
                later,
                0.0,
                MoonSample::moonAltitudeDegrees);
        if (crossing.isBefore(earlier.instant())) {
            return earlier.instant();
        }
        if (crossing.isAfter(later.instant())) {
            return later.instant();
        }
        return crossing;
    }

    public record Result(
            MoonSample current,
            ActivePass activePass,
            Boundary nextRiseBoundary,
            NextPass nextPass
    ) {
        public Result {
            Objects.requireNonNull(current, "current");
            boolean belowHorizon = current.moonAltitudeDegrees() < 0.0;
            if (belowHorizon != (nextRiseBoundary != null)) {
                throw new IllegalArgumentException("Only below-horizon results include a next-rise boundary.");
            }
            if (nextPass != null && (!belowHorizon || !nextPass.startBoundary().equals(nextRiseBoundary))) {
                throw new IllegalArgumentException("Next passes require the below-horizon next-rise boundary.");
            }
        }
    }

    public record ActivePass(
            Boundary startBoundary,
            Boundary endBoundary,
            Instant representedStartsAt,
            Instant representedEndsAt,
            List<MoonSample> pathSamples
    ) {
        public ActivePass {
            Objects.requireNonNull(startBoundary, "startBoundary");
            Objects.requireNonNull(endBoundary, "endBoundary");
            Objects.requireNonNull(representedStartsAt, "representedStartsAt");
            Objects.requireNonNull(representedEndsAt, "representedEndsAt");
            pathSamples = List.copyOf(pathSamples);
        }
    }

    public record NextPass(
            Boundary startBoundary,
            Boundary endBoundary,
            Instant representedStartsAt,
            Instant representedEndsAt,
            List<MoonSample> pathSamples
    ) {
        public NextPass {
            Objects.requireNonNull(startBoundary, "startBoundary");
            Objects.requireNonNull(endBoundary, "endBoundary");
            Objects.requireNonNull(representedStartsAt, "representedStartsAt");
            Objects.requireNonNull(representedEndsAt, "representedEndsAt");
            if (!representedEndsAt.isAfter(representedStartsAt)) {
                throw new IllegalArgumentException("Next passes need a non-zero duration.");
            }
            pathSamples = List.copyOf(pathSamples);
        }
    }

    public record Boundary(BoundaryStatus status, Instant at) {
        public Boundary {
            Objects.requireNonNull(status, "status");
            if ((status == BoundaryStatus.FOUND) != (at != null)) {
                throw new IllegalArgumentException("Found boundaries require an instant; missing boundaries forbid one.");
            }
        }
    }

    public enum BoundaryStatus {
        FOUND,
        NOT_FOUND_WITHIN_RANGE
    }
}
