package dev.moonservice.backend.events;

import dev.moonservice.backend.events.MoonEventResponse.DisplayInterval;
import dev.moonservice.backend.events.MoonEventResponse.Interval;
import dev.moonservice.backend.events.MoonEventResponse.LocalViewing;
import dev.moonservice.backend.events.MoonEventResponse.MoonPath;
import dev.moonservice.backend.events.MoonEventResponse.MoonPathSample;
import dev.moonservice.backend.events.MoonEventResponse.MoonPosition;
import dev.moonservice.backend.events.MoonEventResponse.SunPosition;
import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import dev.moonservice.scoringprototype.window.RefinedTimeGrid;
import dev.moonservice.scoringprototype.window.WindowGenerator;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

final class EventLocalViewing {
    private EventLocalViewing() {
    }

    static Result calculate(
            EphemerisSampler ephemeris,
            Location location,
            Instant eventDomainStartsAt,
            Instant eventDomainEndsAt,
            Instant pathDomainStartsAt,
            Instant pathDomainEndsAt,
            List<Instant> pathSpecialInstants,
            Instant horizonStartsAt,
            Instant horizonEndsAt,
            Instant preferredAt
    ) {
        Map<Instant, MoonSample> sampleCache = new HashMap<>();
        Function<Instant, MoonSample> samples = instant ->
                sampleCache.computeIfAbsent(instant, key -> ephemeris.sampleAt(location, key));
        List<RefinedTimeGrid.Interval> visible = RefinedTimeGrid.matchingIntervals(
                eventDomainStartsAt,
                eventDomainStartsAt,
                eventDomainEndsAt,
                List.of(preferredAt),
                instant -> samples.apply(instant).moonAltitudeDegrees() >= 0.0);
        List<RefinedTimeGrid.Interval> overlapping = visible.stream()
                .filter(interval -> overlaps(interval, horizonStartsAt, horizonEndsAt))
                .toList();
        if (overlapping.isEmpty()) {
            return new Result(visible, null, null);
        }

        RefinedTimeGrid.Interval selected = select(overlapping, preferredAt);
        RefinedTimeGrid.Interval display = new RefinedTimeGrid.Interval(
                max(selected.startsAt(), horizonStartsAt),
                min(selected.endsAt(), horizonEndsAt));
        Instant suggestedAt = bestVisibleAt(
                display, preferredAt, display.endsAt().equals(horizonEndsAt));
        MoonSample suggested = samples.apply(suggestedAt);
        List<Instant> pathInstants = Stream.concat(
                        Stream.of(eventDomainStartsAt, eventDomainEndsAt, preferredAt, suggestedAt),
                        pathSpecialInstants.stream())
                .filter(instant -> inside(instant, pathDomainStartsAt, pathDomainEndsAt))
                .distinct()
                .toList();
        RefinedTimeGrid.Interval matchingPath = RefinedTimeGrid.matchingIntervals(
                        pathDomainStartsAt,
                        pathDomainStartsAt,
                        pathDomainEndsAt,
                        pathInstants,
                        instant -> samples.apply(instant).moonAltitudeDegrees() >= 0.0).stream()
                .filter(interval -> contains(interval, suggestedAt))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The selected event interval had no matching Moon path."));
        RefinedTimeGrid.Interval path = new RefinedTimeGrid.Interval(
                min(matchingPath.startsAt(), selected.startsAt()),
                max(matchingPath.endsAt(), selected.endsAt()));
        List<MoonPathSample> pathSamples = WindowGenerator.pathSamples(
                        samples::apply,
                        path.startsAt(),
                        pathInstants.stream().filter(instant -> contains(path, instant)).toList(),
                        path.endsAt()).stream()
                .map(EventLocalViewing::pathSample)
                .toList();
        LocalViewing localViewing = new LocalViewing(
                intervals(visible),
                interval(selected),
                new DisplayInterval(
                        display.startsAt().toString(),
                        suggestedAt.toString(),
                        display.endsAt().toString(),
                        moonPosition(suggested),
                        new SunPosition(
                                suggested.sunAltitudeDegrees(),
                                ScoringModel.lightBucket(suggested.sunAltitudeDegrees()))),
                new MoonPath(pathSamples));
        return new Result(visible, localViewing, suggested);
    }

    static boolean overlaps(
            RefinedTimeGrid.Interval interval,
            Instant startsAt,
            Instant endsAt
    ) {
        return interval.startsAt().isBefore(endsAt) && interval.endsAt().isAfter(startsAt);
    }

    static Instant max(Instant left, Instant right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    static Instant min(Instant left, Instant right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    static RefinedTimeGrid.Interval select(
            List<RefinedTimeGrid.Interval> intervals,
            Instant preferredAt
    ) {
        return intervals.stream().min(
                Comparator.comparing((RefinedTimeGrid.Interval interval) ->
                                !contains(interval, preferredAt))
                        .thenComparing(interval -> distance(interval, preferredAt))
                        .thenComparing(RefinedTimeGrid.Interval::startsAt))
                .orElseThrow();
    }

    private static Instant bestVisibleAt(
            RefinedTimeGrid.Interval display,
            Instant preferredAt,
            boolean endExclusive
    ) {
        boolean inside = !preferredAt.isBefore(display.startsAt())
                && (endExclusive
                ? preferredAt.isBefore(display.endsAt())
                : !preferredAt.isAfter(display.endsAt()));
        if (inside) {
            return preferredAt;
        }
        if (preferredAt.isBefore(display.startsAt())) {
            return display.startsAt();
        }
        return endExclusive
                ? max(display.startsAt(), display.endsAt().minusSeconds(1))
                : display.endsAt();
    }

    private static boolean contains(RefinedTimeGrid.Interval interval, Instant instant) {
        return !instant.isBefore(interval.startsAt()) && !instant.isAfter(interval.endsAt());
    }

    private static boolean inside(Instant instant, Instant startsAt, Instant endsAt) {
        return !instant.isBefore(startsAt) && !instant.isAfter(endsAt);
    }

    private static Duration distance(RefinedTimeGrid.Interval interval, Instant instant) {
        if (instant.isBefore(interval.startsAt())) {
            return Duration.between(instant, interval.startsAt());
        }
        if (instant.isAfter(interval.endsAt())) {
            return Duration.between(interval.endsAt(), instant);
        }
        return Duration.ZERO;
    }

    private static List<Interval> intervals(List<RefinedTimeGrid.Interval> spans) {
        return spans.stream().map(EventLocalViewing::interval).toList();
    }

    private static Interval interval(RefinedTimeGrid.Interval span) {
        return new Interval(span.startsAt().toString(), span.endsAt().toString());
    }

    private static MoonPosition moonPosition(MoonSample sample) {
        return new MoonPosition(sample.moonAltitudeDegrees(), sample.moonAzimuthDegrees());
    }

    private static MoonPathSample pathSample(MoonSample sample) {
        return new MoonPathSample(
                sample.instant().toString(),
                sample.moonAltitudeDegrees(),
                sample.moonAzimuthDegrees(),
                sample.moonPhaseAngleDegrees(),
                sample.brightLimbTiltDegrees(),
                sample.northPoleTiltDegrees(),
                sample.sunAltitudeDegrees(),
                sample.sunAzimuthDegrees(),
                ScoringModel.lightBucket(sample.sunAltitudeDegrees()),
                null);
    }

    record Result(
            List<RefinedTimeGrid.Interval> visibleIntervals,
            LocalViewing localViewing,
            MoonSample suggestedSample
    ) {
        Result {
            visibleIntervals = List.copyOf(visibleIntervals);
        }
    }
}
