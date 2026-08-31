package dev.moonservice.backend.opportunity.planning;

import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.opportunity.planning.MoonPlanningResponse.Candidates;
import dev.moonservice.backend.opportunity.planning.MoonPlanningResponse.EmptyReason;
import dev.moonservice.backend.opportunity.planning.MoonPlanningResponse.LocationCandidate;
import dev.moonservice.backend.opportunity.planning.MoonPlanningResponse.PlanningWindow;
import dev.moonservice.backend.opportunity.planning.MoonPlanningResponse.Status;
import dev.moonservice.backend.opportunity.planning.MoonPlanningResponse.Success;
import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import dev.moonservice.scoringprototype.output.OpportunityIds;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import dev.moonservice.scoringprototype.window.MoonWindow;
import dev.moonservice.scoringprototype.window.OpportunityHardFilter;
import dev.moonservice.scoringprototype.window.RefinedTimeGrid;
import dev.moonservice.scoringprototype.window.WindowGenerator;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public final class MoonPlanningService {
    static final int PLANNING_HORIZON_DAYS = 365;
    private static final double MAX_MOON_ALTITUDE_DEGREES = 90.0;
    private static final Duration KIND_SAMPLE_OFFSET = Duration.ofMinutes(1);
    private static final Duration PLANNING_HORIZON = planningHorizon();

    private final LocationResolver locationResolver;
    private final Clock clock;
    private final EphemerisSampler ephemeris = new EphemerisSampler();
    private final WindowGenerator windowGenerator = new WindowGenerator();
    private final OpportunityHardFilter hardFilter = new OpportunityHardFilter();

    public MoonPlanningService(LocationResolver locationResolver, Clock clock) {
        this.locationResolver = Objects.requireNonNull(locationResolver, "locationResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MoonPlanningResponse search(
            String locationId,
            OpportunityPreferences preferences,
            List<String> ignoredPreferenceFields,
            int ignoredPreferenceFieldCount
    ) {
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(ignoredPreferenceFields, "ignoredPreferenceFields");
        Instant capturedAt = clock.instant();
        LocationResolution resolution = locationResolver.resolveLocationId(locationId);
        if (resolution.isAmbiguous()) {
            return candidates(capturedAt, resolution.candidates());
        }
        if (resolution.isTemporarilyUnavailable()) {
            return status(
                    "temporarily_unavailable",
                    capturedAt,
                    "Location lookup is temporarily unavailable.");
        }
        return resolution.singleCandidate()
                .<MoonPlanningResponse>map(location -> calculate(
                        capturedAt,
                        location,
                        preferences,
                        ignoredPreferenceFields,
                        ignoredPreferenceFieldCount))
                .orElseGet(() -> status("location_not_found", capturedAt, "No matching location found."));
    }

    private Success calculate(
            Instant startsAt,
            ResolvedLocation resolvedLocation,
            OpportunityPreferences preferences,
            List<String> ignoredPreferenceFields,
            int ignoredPreferenceFieldCount
    ) {
        Instant endsAt = startsAt.plus(PLANNING_HORIZON);
        Location location = prototypeLocation(resolvedLocation);
        Map<Instant, MoonSample> sampleCache = new HashMap<>();
        WindowGenerator.SampleProvider samples = instant ->
                sampleCache.computeIfAbsent(instant, key -> ephemeris.sampleAt(location, key));
        List<MoonWindow> naturalWindows = windowGenerator.findWindows(
                location,
                startsAt,
                endsAt,
                MAX_MOON_ALTITUDE_DEGREES,
                samples);
        OpportunityHardFilter.Result filtered = hardFilter.filter(
                location,
                naturalWindows,
                samples,
                instant -> ephemeris.topocentricLunarAngularRadiusDegrees(location, instant),
                preferences,
                startsAt);
        PlanningCandidate earliest = filtered.windows().stream()
                .map(window -> candidate(window, samples, endsAt))
                .filter(candidate -> ScoringModel.ordinaryVisibilityRejectionReason(candidate.suggested()).isEmpty())
                .sorted(Comparator.comparing((PlanningCandidate candidate) -> candidate.window().startsAt())
                        .thenComparing(candidate -> candidate.suggested().instant())
                        .thenComparing(PlanningCandidate::id))
                .findFirst()
                .orElse(null);
        PlanningWindow nextWindow = earliest == null
                ? null
                : planningWindow(
                        earliest,
                        resolvedLocation.zoneId().getId(),
                        azimuthMatchIntervals(filtered, preferences, earliest));
        EmptyReason emptyReason = earliest == null
                ? new EmptyReason(
                        "no_planning_date",
                        "No matching Moon date was found in the next %d days."
                                .formatted(PLANNING_HORIZON_DAYS))
                : null;
        return new Success(
                "ok",
                startsAt.toString(),
                startsAt.toString(),
                endsAt.toString(),
                PLANNING_HORIZON_DAYS,
                responseLocation(resolvedLocation),
                preferences.version(),
                preferences.normalizedFilters(),
                ignoredPreferenceFields,
                ignoredPreferenceFieldCount,
                Math.max(0, ignoredPreferenceFieldCount - ignoredPreferenceFields.size()),
                nextWindow,
                emptyReason);
    }

    private static PlanningCandidate candidate(
            MoonWindow window,
            WindowGenerator.SampleProvider samples,
            Instant exclusiveEnd
    ) {
        MoonSample suggested = window.suggested();
        String windowKind = window.kind();
        if (!suggested.instant().isBefore(exclusiveEnd)) {
            suggested = endpointSafeSuggestion(window, samples, exclusiveEnd);
            windowKind = windowKind(window, suggested, samples);
        }
        String id = OpportunityIds.format(window.location().slug(), suggested.instant());
        return new PlanningCandidate(window, suggested, id, windowKind);
    }

    private static MoonSample endpointSafeSuggestion(
            MoonWindow window,
            WindowGenerator.SampleProvider samples,
            Instant exclusiveEnd
    ) {
        Instant intervalEnd = window.endsAt().isBefore(exclusiveEnd)
                ? window.endsAt()
                : exclusiveEnd;
        if (!intervalEnd.isAfter(window.startsAt())) {
            throw new IllegalStateException("Planning window has no suggestion inside the search interval.");
        }
        MoonSample best = samples.sampleAt(window.startsAt());
        for (Instant instant : RefinedTimeGrid.sampleInstants(
                window.passStartsAt(), window.startsAt(), intervalEnd, List.of())) {
            if (instant.isBefore(intervalEnd)) {
                MoonSample candidate = samples.sampleAt(instant);
                if (betterCandidate(candidate, best)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static boolean betterCandidate(MoonSample candidate, MoonSample best) {
        int candidateFit = ScoringModel.candidateFit(candidate);
        int bestFit = ScoringModel.candidateFit(best);
        return candidateFit > bestFit
                || candidateFit == bestFit && candidate.instant().isBefore(best.instant());
    }

    private static String windowKind(
            MoonWindow window,
            MoonSample suggested,
            WindowGenerator.SampleProvider samples
    ) {
        Instant middle = window.startsAt().plus(
                Duration.between(window.startsAt(), window.endsAt()).dividedBy(2));
        Instant startCandidate = window.startsAt().plus(KIND_SAMPLE_OFFSET);
        Instant endCandidate = window.endsAt().minus(KIND_SAMPLE_OFFSET);
        Instant startProbe = startCandidate.isBefore(middle) ? startCandidate : middle;
        Instant endProbe = endCandidate.isAfter(middle) ? endCandidate : middle;
        String trend = samples.sampleAt(endProbe).moonAltitudeDegrees()
                >= samples.sampleAt(startProbe).moonAltitudeDegrees() ? "moonrise" : "moonset";
        double altitude = suggested.moonAltitudeDegrees();
        String band = altitude <= 12.0 ? "low" : altitude <= 40.0 ? "context" : "high_context";
        return trend + "_" + band;
    }

    private static PlanningWindow planningWindow(
            PlanningCandidate candidate,
            String timezone,
            List<OpportunityHardFilter.MatchInterval> azimuthMatchIntervals
    ) {
        MoonWindow window = candidate.window();
        MoonSample suggested = candidate.suggested();
        return new PlanningWindow(
                candidate.id(),
                candidate.windowKind(),
                moonPass(window, azimuthMatchIntervals),
                window.startsAt().toString(),
                suggested.instant().toString(),
                window.endsAt().toString(),
                timezone,
                new MoonPlanningResponse.Moon(
                        suggested.moonAltitudeDegrees(),
                        suggested.moonAzimuthDegrees(),
                        suggested.moonIlluminationPercent(),
                        suggested.moonPhaseAngleDegrees(),
                        suggested.brightLimbTiltDegrees(),
                        suggested.northPoleTiltDegrees(),
                        NamedPhase.fromPhaseAngleDegrees(suggested.moonPhaseAngleDegrees()).wireValue()),
                new MoonPlanningResponse.Sun(
                        suggested.sunAltitudeDegrees(),
                        suggested.sunAzimuthDegrees(),
                        ScoringModel.lightBucket(suggested.sunAltitudeDegrees())));
    }

    private static List<OpportunityHardFilter.MatchInterval> azimuthMatchIntervals(
            OpportunityHardFilter.Result filtered,
            OpportunityPreferences preferences,
            PlanningCandidate candidate
    ) {
        if (preferences.azimuthDegrees() == null) {
            return null;
        }
        List<OpportunityHardFilter.MatchInterval> intervals =
                filtered.azimuthMatchIntervals().get(candidate.window().passId());
        if (intervals == null) {
            throw new IllegalStateException("Azimuth filter has no mask for the selected Moon pass.");
        }
        return intervals;
    }

    private static MoonPlanningResponse.MoonPass moonPass(
            MoonWindow window,
            List<OpportunityHardFilter.MatchInterval> azimuthMatchIntervals
    ) {
        List<MoonSample> samples = window.passPathSamples();
        List<MoonPlanningResponse.MoonPathPoint> points = samples.stream()
                .map(sample -> moonPathPoint(sample, roleForPass(window, sample)))
                .toList();
        List<MoonPlanningResponse.AzimuthMatchInterval> responseIntervals =
                azimuthMatchIntervals == null ? null : azimuthMatchIntervals.stream()
                        .map(interval -> new MoonPlanningResponse.AzimuthMatchInterval(
                                interval.startsAt().toString(),
                                interval.endsAt().toString()))
                        .toList();
        return new MoonPlanningResponse.MoonPass(
                window.passId(),
                window.passStartsAt().toString(),
                window.passEndsAt().toString(),
                new MoonPlanningResponse.MoonPassPath(
                        moonPathPoint(samples.getFirst(), "start"),
                        moonPathPoint(samples.getLast(), "end"),
                        points),
                responseIntervals);
    }

    private static MoonPlanningResponse.MoonPathPoint moonPathPoint(
            MoonSample sample,
            String role
    ) {
        return new MoonPlanningResponse.MoonPathPoint(
                sample.instant().toString(),
                sample.moonAltitudeDegrees(),
                sample.moonAzimuthDegrees(),
                sample.moonPhaseAngleDegrees(),
                sample.brightLimbTiltDegrees(),
                sample.northPoleTiltDegrees(),
                sample.sunAltitudeDegrees(),
                sample.sunAzimuthDegrees(),
                ScoringModel.lightBucket(sample.sunAltitudeDegrees()),
                role);
    }

    private static String roleForPass(MoonWindow window, MoonSample sample) {
        if (sample.instant().equals(window.passStartsAt())) {
            return "start";
        }
        if (sample.instant().equals(window.passEndsAt())) {
            return "end";
        }
        return "path";
    }

    private static MoonPlanningResponse.Location responseLocation(ResolvedLocation location) {
        return new MoonPlanningResponse.Location(
                location.locationId(),
                "real_location",
                location.displayName(),
                location.zoneId().getId(),
                location.countryCode());
    }

    private static Location prototypeLocation(ResolvedLocation location) {
        return new Location(
                location.locationId(),
                "real_location",
                location.locationId(),
                location.displayName(),
                location.latitude(),
                location.longitude(),
                location.elevationMeters(),
                location.zoneId().getId(),
                location.countryCode());
    }

    private static Candidates candidates(Instant generatedAt, List<ResolvedLocation> locations) {
        return new Candidates(
                "ambiguous_location",
                generatedAt.toString(),
                locations.stream().map(location -> new LocationCandidate(
                        "real_location",
                        location.locationId(),
                        location.displayName(),
                        location.countryCode(),
                        location.zoneId().getId())).toList());
    }

    private static Status status(String status, Instant generatedAt, String message) {
        return new Status(status, generatedAt.toString(), message);
    }

    private static Duration planningHorizon() {
        if (PLANNING_HORIZON_DAYS <= 0) {
            throw new IllegalStateException("PLANNING_HORIZON_DAYS must be positive.");
        }
        return Duration.ofDays(PLANNING_HORIZON_DAYS);
    }

    private record PlanningCandidate(
            MoonWindow window,
            MoonSample suggested,
            String id,
            String windowKind
    ) {
    }
}
