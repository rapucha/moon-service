package dev.moonservice.backend.events;

import dev.moonservice.backend.events.EventPreferenceEvaluator.TimeSpan;
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
import java.util.function.ToDoubleFunction;

@Service
public final class LunarEclipseEventService {
    private static final int HORIZON_MONTHS = 18;
    private static final Duration ECLIPSE_SEARCH_LOOKBACK = Duration.ofDays(1);

    private final LocationResolver locationResolver;
    private final WeatherForecastProvider weatherForecastProvider;
    private final OpportunitySearchDefaults opportunitySearchDefaults;
    private final Clock clock;
    private final EphemerisSampler ephemeris = new EphemerisSampler();
    private final EventPreferenceEvaluator preferenceEvaluator = new EventPreferenceEvaluator();

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
        TimeSpan objective = phaseSpecs.getFirst().span();
        Map<Instant, MoonSample> sampleCache = new HashMap<>();
        Map<Instant, Double> radiusCache = new HashMap<>();
        Function<Instant, MoonSample> samples = instant ->
                sampleCache.computeIfAbsent(instant, key -> ephemeris.sampleAt(location, key));
        ToDoubleFunction<Instant> radii = instant -> radiusCache.computeIfAbsent(
                instant, key -> ephemeris.topocentricLunarAngularRadiusDegrees(location, key));
        List<TimeSpan> visible = RefinedTimeGrid.matchingIntervals(
                objective.startsAt(), objective.startsAt(), objective.endsAt(),
                List.of(maximumAt), instant -> samples.apply(instant).moonAltitudeDegrees() >= 0.0)
                .stream().map(interval -> new TimeSpan(interval.startsAt(), interval.endsAt())).toList();
        List<TimeSpan> overlapping = visible.stream()
                .filter(interval -> overlaps(interval, horizonStart, horizonEnd))
                .toList();
        if (overlapping.isEmpty()) {
            return null;
        }

        TimeSpan selected = select(overlapping, maximumAt);
        TimeSpan display = new TimeSpan(
                max(selected.startsAt(), horizonStart),
                min(selected.endsAt(), horizonEnd));
        List<Instant> extras = assessmentInstants(phaseSpecs, visible, selected, display, maximumAt);
        EventPreferenceEvaluator.Result evaluation = preferenceEvaluator.evaluate(
                location, preferences, objective.startsAt(), maximumAt,
                display, display.endsAt().equals(horizonEnd), extras, samples, radii);
        MoonSample maximum = samples.apply(maximumAt);
        MoonSample suggested = samples.apply(evaluation.suggestedAt());
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
                moonPosition(maximum),
                new EventVisibility(
                        visibilityStatus(objective, visible),
                        intervals(visible),
                        interval(selected),
                        new DisplayInterval(
                                display.startsAt().toString(),
                                evaluation.suggestedAt().toString(),
                                display.endsAt().toString(),
                                moonPosition(suggested),
                                new SunPosition(
                                        suggested.sunAltitudeDegrees(),
                                        ScoringModel.lightBucket(suggested.sunAltitudeDegrees())))),
                evaluation.assessment(),
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

    private static EclipsePhase phase(PhaseSpec phase, List<TimeSpan> visible) {
        List<TimeSpan> intersections = intersections(phase.span(), visible);
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

    private static List<Instant> assessmentInstants(
            List<PhaseSpec> phases,
            List<TimeSpan> visible,
            TimeSpan selected,
            TimeSpan display,
            Instant maximumAt
    ) {
        List<Instant> instants = new ArrayList<>();
        phases.forEach(phase -> addBounds(instants, phase.span()));
        visible.forEach(interval -> addBounds(instants, interval));
        addBounds(instants, selected);
        addBounds(instants, display);
        instants.add(maximumAt);
        return List.copyOf(instants);
    }

    private static void addBounds(List<Instant> instants, TimeSpan span) {
        instants.add(span.startsAt());
        instants.add(span.endsAt());
    }

    private static List<TimeSpan> intersections(TimeSpan objective, List<TimeSpan> intervals) {
        return intervals.stream()
                .filter(interval -> overlaps(interval, objective.startsAt(), objective.endsAt()))
                .map(interval -> new TimeSpan(
                        max(interval.startsAt(), objective.startsAt()),
                        min(interval.endsAt(), objective.endsAt())))
                .toList();
    }

    static TimeSpan select(List<TimeSpan> intervals, Instant maximumAt) {
        return intervals.stream().min(
                Comparator.comparing((TimeSpan interval) -> !contains(interval, maximumAt))
                        .thenComparing(interval -> distance(interval, maximumAt))
                        .thenComparing(TimeSpan::startsAt))
                .orElseThrow();
    }

    private static String visibilityStatus(TimeSpan objective, List<TimeSpan> visible) {
        if (visible.isEmpty()) {
            return "not_visible";
        }
        TimeSpan first = visible.getFirst();
        return visible.size() == 1
                && !first.startsAt().isAfter(objective.startsAt())
                && !first.endsAt().isBefore(objective.endsAt())
                ? "fully_visible" : "partly_visible";
    }

    private static List<Interval> intervals(List<TimeSpan> spans) {
        return spans.stream().map(LunarEclipseEventService::interval).toList();
    }

    private static Interval interval(TimeSpan span) {
        return new Interval(span.startsAt().toString(), span.endsAt().toString());
    }

    private static MoonPosition moonPosition(MoonSample sample) {
        return new MoonPosition(sample.moonAltitudeDegrees(), sample.moonAzimuthDegrees());
    }

    private static LunarEclipseEvent withWeather(LunarEclipseEvent event, Weather weather) {
        return new LunarEclipseEvent(
                event.id(), event.kind(), event.subtype(), event.startsAt(), event.maximumAt(), event.endsAt(),
                event.umbralObscurationPercent(), event.phases(), event.moonAtMaximum(),
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

    private static TimeSpan around(Time maximumAt, double semiDurationMinutes) {
        double semiDurationDays = semiDurationMinutes / (24.0 * 60.0);
        return new TimeSpan(
                instant(maximumAt.addDays(-semiDurationDays)),
                instant(maximumAt.addDays(semiDurationDays)));
    }

    private static Time astronomyTime(Instant instant) {
        return Time.fromMillisecondsSince1970(instant.toEpochMilli());
    }

    private static Instant instant(Time time) {
        return Instant.ofEpochMilli(time.toMillisecondsSince1970());
    }

    private static boolean overlaps(TimeSpan interval, Instant startsAt, Instant endsAt) {
        return interval.startsAt().isBefore(endsAt) && interval.endsAt().isAfter(startsAt);
    }

    private static boolean contains(TimeSpan interval, Instant instant) {
        return !instant.isBefore(interval.startsAt()) && !instant.isAfter(interval.endsAt());
    }

    private static boolean contains(Instant startsAt, Instant endsAt, Instant instant) {
        return !instant.isBefore(startsAt) && instant.isBefore(endsAt);
    }

    private static Duration distance(TimeSpan interval, Instant instant) {
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

    private record PhaseSpec(String kind, TimeSpan span) {
    }
}
