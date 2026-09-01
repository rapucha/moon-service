package dev.moonservice.backend.events;

import dev.moonservice.backend.events.MoonEventResponse.*;
import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.window.RefinedTimeGrid;
import io.github.cosinekitty.astronomy.Astronomy;
import io.github.cosinekitty.astronomy.EclipseKind;
import io.github.cosinekitty.astronomy.LunarEclipseInfo;
import io.github.cosinekitty.astronomy.Time;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public final class LunarEclipseEventService {
    private static final Duration ECLIPSE_SEARCH_LOOKBACK = Duration.ofDays(1);
    private static final Duration PATH_RADIUS = Duration.ofHours(24);

    private final EphemerisSampler ephemeris = new EphemerisSampler();
    private final LunarEclipseShadowSamples shadowSamples = new LunarEclipseShadowSamples(ephemeris);

    List<LunarEclipseEvent> discover(
            Instant startsAt,
            Instant endsAt,
            Location location,
            OpportunityPreferences preferences
    ) {
        List<LunarEclipseEvent> events = new ArrayList<>();
        LunarEclipseInfo eclipse = Astronomy.searchLunarEclipse(
                astronomyTime(startsAt.minus(ECLIPSE_SEARCH_LOOKBACK)));
        while (objectiveStart(eclipse).isBefore(endsAt)) {
            if (objectiveEnd(eclipse).isAfter(startsAt)) {
                LunarEclipseEvent event = event(
                        eclipse, startsAt, endsAt, location, preferences);
                if (event != null) {
                    events.add(event);
                }
            }
            eclipse = Astronomy.nextLunarEclipse(eclipse.getPeak());
        }
        return events.stream()
                .sorted(Comparator.comparing(LunarEclipseEvent::maximumAt)
                        .thenComparing(LunarEclipseEvent::id))
                .toList();
    }

    private LunarEclipseEvent event(
            LunarEclipseInfo eclipse,
            Instant horizonStart,
            Instant horizonEnd,
            Location location,
            OpportunityPreferences preferences
    ) {
        Instant maximumAt = instant(eclipse.getPeak());
        List<PhaseSpec> phaseSpecs = phases(eclipse);
        RefinedTimeGrid.Interval objective = phaseSpecs.getFirst().span();
        EventLocalViewing.Result viewing = EventLocalViewing.calculate(
                ephemeris,
                location,
                objective.startsAt(),
                objective.endsAt(),
                maximumAt.minus(PATH_RADIUS),
                maximumAt.plus(PATH_RADIUS),
                phaseSpecs.stream()
                        .flatMap(phase -> Stream.of(
                                phase.span().startsAt(), phase.span().endsAt()))
                        .toList(),
                horizonStart,
                horizonEnd,
                maximumAt);
        if (viewing.localViewing() == null) {
            // Lunar eclipses keep their existing visible-from-location contract.
            // NearPerigeeFullMoonService separately retains qualifying full Moons
            // whose peak is in the horizon even when no local viewing overlaps it.
            return null;
        }

        MoonSample maximum = ephemeris.sampleAt(location, maximumAt);
        LocalViewing localViewing = viewing.localViewing();
        Instant suggestedAt = Instant.parse(localViewing.displayInterval().suggestedAt());
        MoonPath moonPath = shadowSamples.withPathShadows(location, localViewing.moonPath());
        List<EclipsePhase> responsePhases = phaseSpecs.stream()
                .map(phase -> phase(phase, viewing.visibleIntervals()))
                .toList();
        return new LunarEclipseEvent(
                stableId(maximumAt),
                "lunar_eclipse",
                subtype(eclipse.getKind()),
                objective.startsAt().toString(),
                maximumAt.toString(),
                objective.endsAt().toString(),
                eclipse.getObscuration() * 100.0,
                responsePhases,
                shadowSamples.sample(
                        location,
                        phaseSpecs.stream().map(PhaseSpec::span).toList(),
                        maximumAt,
                        suggestedAt),
                moonPosition(maximum),
                new EventVisibility(
                        visibilityStatus(objective, viewing.visibleIntervals()),
                        localViewing.intervals(),
                        localViewing.selectedInterval(),
                        localViewing.displayInterval(),
                        moonPath),
                EventPreferenceEvaluator.evaluate(
                        preferences,
                        viewing.suggestedSample(),
                        ephemeris.topocentricLunarAngularRadiusDegrees(
                                location, suggestedAt)),
                Weather.outsideForecastHorizon());
    }

    private static EclipsePhase phase(
            PhaseSpec phase,
            List<RefinedTimeGrid.Interval> visible
    ) {
        List<RefinedTimeGrid.Interval> intersections = intersections(phase.span(), visible);
        return new EclipsePhase(
                phase.kind(),
                phase.span().startsAt().toString(),
                phase.span().endsAt().toString(),
                new PhaseVisibility(
                        visibilityStatus(phase.span(), intersections),
                        intervals(intersections)));
    }

    private static List<PhaseSpec> phases(LunarEclipseInfo eclipse) {
        List<PhaseSpec> phases = new ArrayList<>();
        phases.add(new PhaseSpec("penumbral", around(eclipse.getPeak(), eclipse.getSdPenum())));
        if (eclipse.getSdPartial() > 0.0) {
            phases.add(new PhaseSpec("partial", around(eclipse.getPeak(), eclipse.getSdPartial())));
        }
        if (eclipse.getSdTotal() > 0.0) {
            phases.add(new PhaseSpec("total", around(eclipse.getPeak(), eclipse.getSdTotal())));
        }
        return List.copyOf(phases);
    }

    private static List<RefinedTimeGrid.Interval> intersections(
            RefinedTimeGrid.Interval objective,
            List<RefinedTimeGrid.Interval> intervals
    ) {
        return intervals.stream()
                .filter(interval -> EventLocalViewing.overlaps(
                        interval, objective.startsAt(), objective.endsAt()))
                .map(interval -> new RefinedTimeGrid.Interval(
                        EventLocalViewing.max(interval.startsAt(), objective.startsAt()),
                        EventLocalViewing.min(interval.endsAt(), objective.endsAt())))
                .toList();
    }

    private static String visibilityStatus(
            RefinedTimeGrid.Interval objective,
            List<RefinedTimeGrid.Interval> visible
    ) {
        if (visible.isEmpty()) {
            return "not_visible";
        }
        RefinedTimeGrid.Interval first = visible.getFirst();
        return visible.size() == 1
                && !first.startsAt().isAfter(objective.startsAt())
                && !first.endsAt().isBefore(objective.endsAt())
                ? "fully_visible" : "partly_visible";
    }

    private static List<Interval> intervals(List<RefinedTimeGrid.Interval> spans) {
        return spans.stream()
                .map(span -> new Interval(
                        span.startsAt().toString(), span.endsAt().toString()))
                .toList();
    }

    private static MoonPosition moonPosition(MoonSample sample) {
        return new MoonPosition(
                sample.moonAltitudeDegrees(), sample.moonAzimuthDegrees());
    }

    private static String stableId(Instant maximumAt) {
        UUID value = UUID.nameUUIDFromBytes(
                ("lunar_eclipse:" + maximumAt).getBytes(StandardCharsets.UTF_8));
        return "lunar-eclipse-" + value;
    }

    private static String subtype(EclipseKind kind) {
        return switch (kind) {
            case Penumbral -> "penumbral";
            case Partial -> "partial";
            case Total -> "total";
            default -> throw new IllegalStateException(
                    "Unexpected lunar eclipse kind: " + kind);
        };
    }

    private static Instant objectiveStart(LunarEclipseInfo eclipse) {
        return around(eclipse.getPeak(), eclipse.getSdPenum()).startsAt();
    }

    private static Instant objectiveEnd(LunarEclipseInfo eclipse) {
        return around(eclipse.getPeak(), eclipse.getSdPenum()).endsAt();
    }

    private static RefinedTimeGrid.Interval around(
            Time maximumAt,
            double semiDurationMinutes
    ) {
        double semiDurationDays = semiDurationMinutes / (24.0 * 60.0);
        return new RefinedTimeGrid.Interval(
                instant(maximumAt.addDays(-semiDurationDays)),
                instant(maximumAt.addDays(semiDurationDays)));
    }

    private static Time astronomyTime(Instant instant) {
        return Time.fromMillisecondsSince1970(instant.toEpochMilli());
    }

    private static Instant instant(Time time) {
        return Instant.ofEpochMilli(time.toMillisecondsSince1970());
    }

    private record PhaseSpec(String kind, RefinedTimeGrid.Interval span) {
    }
}
