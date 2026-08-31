package dev.moonservice.backend.events;

import dev.moonservice.backend.events.MoonEventResponse.*;
import dev.moonservice.backend.location.*;
import dev.moonservice.backend.opportunity.OpportunitySearchDefaults;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.weather.*;
import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import dev.moonservice.scoringprototype.window.RefinedTimeGrid;
import io.github.cosinekitty.astronomy.Astronomy;
import io.github.cosinekitty.astronomy.EclipseKind;
import io.github.cosinekitty.astronomy.LunarEclipseInfo;
import io.github.cosinekitty.astronomy.Time;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

@Service
public final class LunarEclipseEventService {
    private static final int HORIZON_MONTHS = 18;
    private static final Duration ECLIPSE_SEARCH_LOOKBACK = Duration.ofDays(1);

    private final LocationResolver locationResolver;
    private final WeatherForecastProvider weatherForecastProvider;
    private final OpportunitySearchDefaults opportunitySearchDefaults;
    private final Clock clock;
    private final EphemerisSampler ephemeris = new EphemerisSampler();
    private final LunarEclipseShadowSamples shadowSamples = new LunarEclipseShadowSamples(ephemeris);

    public LunarEclipseEventService(
            LocationResolver locationResolver,
            WeatherForecastProvider weatherForecastProvider,
            OpportunitySearchDefaults opportunitySearchDefaults,
            Clock clock
    ) {
        this.locationResolver = Objects.requireNonNull(locationResolver, "locationResolver");
        this.weatherForecastProvider = Objects.requireNonNull(weatherForecastProvider, "weatherForecastProvider");
        this.opportunitySearchDefaults = Objects.requireNonNull(opportunitySearchDefaults, "opportunitySearchDefaults");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MoonEventResponse search(
            String locationId,
            OpportunityPreferences preferences,
            List<String> ignoredPreferenceFields,
            int ignoredPreferenceFieldCount
    ) {
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(ignoredPreferenceFields, "ignoredPreferenceFields");
        Instant generatedAt = clock.instant();
        LocationResolution resolution = locationResolver.resolveLocationId(locationId);
        if (resolution.isAmbiguous()) {
            return candidates(generatedAt, resolution.candidates());
        }
        if (resolution.isTemporarilyUnavailable()) {
            return status("temporarily_unavailable", generatedAt,
                    "Location lookup is temporarily unavailable.");
        }
        return resolution.singleCandidate()
                .<MoonEventResponse>map(location -> calculate(
                        generatedAt, location, preferences,
                        ignoredPreferenceFields, ignoredPreferenceFieldCount))
                .orElseGet(() -> status(
                        "location_not_found", generatedAt, "No matching location found."));
    }

    private Success calculate(
            Instant generatedAt,
            ResolvedLocation resolvedLocation,
            OpportunityPreferences preferences,
            List<String> ignoredPreferenceFields,
            int ignoredPreferenceFieldCount
    ) {
        Instant endsAt = generatedAt.atZone(resolvedLocation.zoneId())
                .plusMonths(HORIZON_MONTHS)
                .toInstant();
        Location location = prototypeLocation(resolvedLocation);
        List<LunarEclipseEvent> events = discover(
                generatedAt, endsAt, location, preferences);
        return new Success(
                "ok",
                generatedAt.toString(),
                generatedAt.toString(),
                endsAt.toString(),
                responseLocation(resolvedLocation),
                preferences.version(),
                preferences.normalizedFilters(),
                ignoredPreferenceFields,
                ignoredPreferenceFieldCount,
                Math.max(0, ignoredPreferenceFieldCount - ignoredPreferenceFields.size()),
                attachWeather(events, resolvedLocation, generatedAt));
    }

    private List<LunarEclipseEvent> discover(
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
                LunarEclipseEvent event = event(eclipse, startsAt, endsAt, location, preferences);
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
        Map<Instant, MoonSample> sampleCache = new HashMap<>();
        Function<Instant, MoonSample> samples = instant ->
                sampleCache.computeIfAbsent(instant, key -> ephemeris.sampleAt(location, key));
        List<RefinedTimeGrid.Interval> visible = RefinedTimeGrid.matchingIntervals(
                objective.startsAt(), objective.startsAt(), objective.endsAt(),
                List.of(maximumAt), instant -> samples.apply(instant).moonAltitudeDegrees() >= 0.0);
        List<RefinedTimeGrid.Interval> overlapping = visible.stream()
                .filter(interval -> overlaps(interval, horizonStart, horizonEnd))
                .toList();
        if (overlapping.isEmpty()) {
            return null;
        }

        RefinedTimeGrid.Interval selected = select(overlapping, maximumAt);
        RefinedTimeGrid.Interval display = new RefinedTimeGrid.Interval(
                max(selected.startsAt(), horizonStart),
                min(selected.endsAt(), horizonEnd));
        Instant suggestedAt = bestVisibleAt(
                display, maximumAt, display.endsAt().equals(horizonEnd));
        MoonSample maximum = samples.apply(maximumAt);
        MoonSample suggested = samples.apply(suggestedAt);
        List<EclipsePhase> responsePhases = phaseSpecs.stream()
                .map(phase -> phase(phase, visible))
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
                        visibilityStatus(objective, visible),
                        intervals(visible),
                        interval(selected),
                        new DisplayInterval(
                                display.startsAt().toString(),
                                suggestedAt.toString(),
                                display.endsAt().toString(),
                                moonPosition(suggested),
                                new SunPosition(
                                        suggested.sunAltitudeDegrees(),
                                        ScoringModel.lightBucket(suggested.sunAltitudeDegrees())))),
                EventPreferenceEvaluator.evaluate(
                        preferences,
                        suggested,
                        ephemeris.topocentricLunarAngularRadiusDegrees(location, suggestedAt)),
                Weather.outsideForecastHorizon());
    }

    private List<LunarEclipseEvent> attachWeather(
            List<LunarEclipseEvent> events,
            ResolvedLocation location,
            Instant generatedAt
    ) {
        OpportunitySearchRequest request = opportunitySearchDefaults.requestFor(
                location, generatedAt, OpportunitySearchRequest.Order.SOONEST);
        Instant forecastStartsAt = request.startDate().atStartOfDay(location.zoneId()).toInstant();
        Instant forecastEndsAt = request.startDate().plusDays(request.forecastHorizonDays())
                .atStartOfDay(location.zoneId()).toInstant();
        boolean lookupNeeded = events.stream().map(LunarEclipseEventService::suggestedAt)
                .anyMatch(instant -> contains(forecastStartsAt, forecastEndsAt, instant));
        WeatherForecast forecast = null;
        boolean lookupFailed = false;
        if (lookupNeeded) {
            try {
                forecast = weatherForecastProvider.forecastFor(
                        location, forecastStartsAt, forecastEndsAt);
            } catch (WeatherForecastUnavailableException ex) {
                lookupFailed = true;
            }
        }

        List<LunarEclipseEvent> result = new ArrayList<>();
        for (LunarEclipseEvent event : events) {
            Instant suggestedAt = suggestedAt(event);
            Weather weather = Weather.outsideForecastHorizon();
            if (contains(forecastStartsAt, forecastEndsAt, suggestedAt)) {
                if (lookupFailed) {
                    weather = Weather.temporarilyUnavailable();
                } else {
                    try {
                        HourlyWeather hour = Objects.requireNonNull(forecast).weatherAt(suggestedAt);
                        weather = new Weather(
                                "available",
                                hour.startsAt().toString(),
                                ScoringModel.weatherSummary(hour.toWeatherFixture()),
                                hour.cloudCoverPercent(),
                                hour.precipitationProbabilityPercent());
                    } catch (WeatherForecastUnavailableException ex) {
                        weather = Weather.temporarilyUnavailable();
                    }
                }
            }
            result.add(withWeather(event, weather));
        }
        return List.copyOf(result);
    }

    private static EclipsePhase phase(PhaseSpec phase, List<RefinedTimeGrid.Interval> visible) {
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

    private static Instant bestVisibleAt(
            RefinedTimeGrid.Interval display,
            Instant maximumAt,
            boolean endExclusive
    ) {
        boolean inside = !maximumAt.isBefore(display.startsAt())
                && (endExclusive
                ? maximumAt.isBefore(display.endsAt())
                : !maximumAt.isAfter(display.endsAt()));
        if (inside) {
            return maximumAt;
        }
        if (maximumAt.isBefore(display.startsAt())) {
            return display.startsAt();
        }
        return endExclusive
                ? max(display.startsAt(), display.endsAt().minusSeconds(1))
                : display.endsAt();
    }

    private static List<RefinedTimeGrid.Interval> intersections(RefinedTimeGrid.Interval objective, List<RefinedTimeGrid.Interval> intervals) {
        return intervals.stream()
                .filter(interval -> overlaps(interval, objective.startsAt(), objective.endsAt()))
                .map(interval -> new RefinedTimeGrid.Interval(
                        max(interval.startsAt(), objective.startsAt()),
                        min(interval.endsAt(), objective.endsAt())))
                .toList();
    }

    static RefinedTimeGrid.Interval select(List<RefinedTimeGrid.Interval> intervals, Instant maximumAt) {
        return intervals.stream().min(
                Comparator.comparing((RefinedTimeGrid.Interval interval) -> !contains(interval, maximumAt))
                        .thenComparing(interval -> distance(interval, maximumAt))
                        .thenComparing(RefinedTimeGrid.Interval::startsAt))
                .orElseThrow();
    }

    private static String visibilityStatus(RefinedTimeGrid.Interval objective, List<RefinedTimeGrid.Interval> visible) {
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
        return spans.stream().map(LunarEclipseEventService::interval).toList();
    }

    private static Interval interval(RefinedTimeGrid.Interval span) {
        return new Interval(span.startsAt().toString(), span.endsAt().toString());
    }

    private static MoonPosition moonPosition(MoonSample sample) {
        return new MoonPosition(sample.moonAltitudeDegrees(), sample.moonAzimuthDegrees());
    }

    private static LunarEclipseEvent withWeather(LunarEclipseEvent event, Weather weather) {
        return new LunarEclipseEvent(
                event.id(), event.kind(), event.subtype(), event.startsAt(), event.maximumAt(), event.endsAt(),
                event.umbralObscurationPercent(), event.phases(), event.shadowSamples(), event.moonAtMaximum(),
                event.localVisibility(), event.preferenceAssessment(), weather);
    }

    private static Instant suggestedAt(LunarEclipseEvent event) {
        return Instant.parse(event.localVisibility().displayInterval().suggestedAt());
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
            default -> throw new IllegalStateException("Unexpected lunar eclipse kind: " + kind);
        };
    }

    private static Instant objectiveStart(LunarEclipseInfo eclipse) {
        return around(eclipse.getPeak(), eclipse.getSdPenum()).startsAt();
    }

    private static Instant objectiveEnd(LunarEclipseInfo eclipse) {
        return around(eclipse.getPeak(), eclipse.getSdPenum()).endsAt();
    }

    private static RefinedTimeGrid.Interval around(Time maximumAt, double semiDurationMinutes) {
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

    private static boolean overlaps(RefinedTimeGrid.Interval interval, Instant startsAt, Instant endsAt) {
        return interval.startsAt().isBefore(endsAt) && interval.endsAt().isAfter(startsAt);
    }

    private static boolean contains(RefinedTimeGrid.Interval interval, Instant instant) {
        return !instant.isBefore(interval.startsAt()) && !instant.isAfter(interval.endsAt());
    }

    private static boolean contains(Instant startsAt, Instant endsAt, Instant instant) {
        return !instant.isBefore(startsAt) && instant.isBefore(endsAt);
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

    private static Instant max(Instant left, Instant right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static Instant min(Instant left, Instant right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static MoonEventResponse.Location responseLocation(ResolvedLocation location) {
        return new MoonEventResponse.Location(
                location.locationId(), "real_location", location.displayName(),
                location.zoneId().getId(), location.countryCode());
    }

    private static Location prototypeLocation(ResolvedLocation location) {
        return new Location(
                location.locationId(), "real_location", location.locationId(), location.displayName(),
                location.latitude(), location.longitude(), location.elevationMeters(),
                location.zoneId().getId(), location.countryCode());
    }

    private static Candidates candidates(Instant generatedAt, List<ResolvedLocation> locations) {
        return new Candidates(
                "ambiguous_location",
                generatedAt.toString(),
                locations.stream().map(location -> new LocationCandidate(
                        "real_location", location.locationId(), location.displayName(),
                        location.countryCode(), location.zoneId().getId())).toList());
    }

    private static Status status(String status, Instant generatedAt, String message) {
        return new Status(status, generatedAt.toString(), message);
    }

    private record PhaseSpec(String kind, RefinedTimeGrid.Interval span) {
    }
}
