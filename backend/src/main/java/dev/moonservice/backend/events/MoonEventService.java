package dev.moonservice.backend.events;

import dev.moonservice.backend.events.MoonEventResponse.Candidates;
import dev.moonservice.backend.events.MoonEventResponse.FullMoonEvent;
import dev.moonservice.backend.events.MoonEventResponse.LunarEclipseEvent;
import dev.moonservice.backend.events.MoonEventResponse.MoonEvent;
import dev.moonservice.backend.events.MoonEventResponse.Status;
import dev.moonservice.backend.events.MoonEventResponse.Success;
import dev.moonservice.backend.events.MoonEventResponse.Weather;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.opportunity.OpportunitySearchDefaults;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.weather.HourlyWeather;
import dev.moonservice.backend.weather.WeatherForecast;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastUnavailableException;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public final class MoonEventService {
    private static final int HORIZON_MONTHS = 18;

    private final LocationResolver locationResolver;
    private final WeatherForecastProvider weatherForecastProvider;
    private final OpportunitySearchDefaults opportunitySearchDefaults;
    private final LunarEclipseEventService lunarEclipseEventService;
    private final NearPerigeeFullMoonService nearPerigeeFullMoonService;
    private final Clock clock;

    public MoonEventService(
            LocationResolver locationResolver,
            WeatherForecastProvider weatherForecastProvider,
            OpportunitySearchDefaults opportunitySearchDefaults,
            LunarEclipseEventService lunarEclipseEventService,
            NearPerigeeFullMoonService nearPerigeeFullMoonService,
            Clock clock
    ) {
        this.locationResolver = Objects.requireNonNull(locationResolver, "locationResolver");
        this.weatherForecastProvider = Objects.requireNonNull(
                weatherForecastProvider, "weatherForecastProvider");
        this.opportunitySearchDefaults = Objects.requireNonNull(
                opportunitySearchDefaults, "opportunitySearchDefaults");
        this.lunarEclipseEventService = Objects.requireNonNull(
                lunarEclipseEventService, "lunarEclipseEventService");
        this.nearPerigeeFullMoonService = Objects.requireNonNull(
                nearPerigeeFullMoonService, "nearPerigeeFullMoonService");
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
            return status(
                    "temporarily_unavailable",
                    generatedAt,
                    "Location lookup is temporarily unavailable.");
        }
        return resolution.singleCandidate()
                .<MoonEventResponse>map(location -> calculate(
                        generatedAt,
                        location,
                        preferences,
                        ignoredPreferenceFields,
                        ignoredPreferenceFieldCount))
                .orElseGet(() -> status(
                        "location_not_found",
                        generatedAt,
                        "No matching location found."));
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
        List<MoonEvent> events = new ArrayList<>();
        events.addAll(lunarEclipseEventService.discover(
                generatedAt, endsAt, location, preferences));
        events.addAll(nearPerigeeFullMoonService.discover(
                generatedAt, endsAt, location, preferences));
        List<MoonEvent> ordered = events.stream()
                .sorted(Comparator.comparing(MoonEventService::objectiveAt)
                        .thenComparing(MoonEvent::id))
                .toList();
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
                attachWeather(ordered, resolvedLocation, generatedAt));
    }

    private List<MoonEvent> attachWeather(
            List<MoonEvent> events,
            ResolvedLocation location,
            Instant generatedAt
    ) {
        OpportunitySearchRequest request = opportunitySearchDefaults.requestFor(
                location, generatedAt, OpportunitySearchRequest.Order.SOONEST);
        Instant forecastStartsAt = request.startDate()
                .atStartOfDay(location.zoneId())
                .toInstant();
        Instant forecastEndsAt = request.startDate()
                .plusDays(request.forecastHorizonDays())
                .atStartOfDay(location.zoneId())
                .toInstant();
        boolean lookupNeeded = events.stream()
                .map(MoonEventService::suggestedAt)
                .filter(Objects::nonNull)
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

        List<MoonEvent> result = new ArrayList<>();
        for (MoonEvent event : events) {
            Instant suggestedAt = suggestedAt(event);
            if (suggestedAt == null) {
                result.add(event);
                continue;
            }
            Weather weather = Weather.outsideForecastHorizon();
            if (contains(forecastStartsAt, forecastEndsAt, suggestedAt)) {
                if (lookupFailed) {
                    weather = Weather.temporarilyUnavailable();
                } else {
                    try {
                        HourlyWeather hour = Objects.requireNonNull(forecast)
                                .weatherAt(suggestedAt);
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

    private static Instant objectiveAt(MoonEvent event) {
        return switch (event) {
            case LunarEclipseEvent eclipse -> Instant.parse(eclipse.maximumAt());
            case FullMoonEvent fullMoon -> Instant.parse(fullMoon.peakAt());
        };
    }

    private static Instant suggestedAt(MoonEvent event) {
        String value = switch (event) {
            case LunarEclipseEvent eclipse ->
                    eclipse.localVisibility().displayInterval().suggestedAt();
            case FullMoonEvent fullMoon -> fullMoon.localViewing() == null
                    ? null
                    : fullMoon.localViewing().displayInterval().suggestedAt();
        };
        return value == null ? null : Instant.parse(value);
    }

    private static MoonEvent withWeather(MoonEvent event, Weather weather) {
        return switch (event) {
            case LunarEclipseEvent eclipse -> new LunarEclipseEvent(
                    eclipse.id(), eclipse.kind(), eclipse.subtype(), eclipse.startsAt(),
                    eclipse.maximumAt(), eclipse.endsAt(), eclipse.umbralObscurationPercent(),
                    eclipse.phases(), eclipse.shadowSamples(), eclipse.moonAtMaximum(),
                    eclipse.localVisibility(), eclipse.preferenceAssessment(), weather);
            case FullMoonEvent fullMoon -> new FullMoonEvent(
                    fullMoon.id(), fullMoon.kind(), fullMoon.peakAt(), fullMoon.qualifiers(),
                    fullMoon.localViewing(), fullMoon.preferenceAssessment(), weather);
        };
    }

    private static boolean contains(Instant startsAt, Instant endsAt, Instant instant) {
        return !instant.isBefore(startsAt) && instant.isBefore(endsAt);
    }

    private static MoonEventResponse.Location responseLocation(ResolvedLocation location) {
        return new MoonEventResponse.Location(
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

    private static Candidates candidates(
            Instant generatedAt,
            List<ResolvedLocation> locations
    ) {
        return new Candidates(
                "ambiguous_location",
                generatedAt.toString(),
                locations.stream().map(location -> new MoonEventResponse.LocationCandidate(
                        "real_location",
                        location.locationId(),
                        location.displayName(),
                        location.countryCode(),
                        location.zoneId().getId())).toList());
    }

    private static Status status(String status, Instant generatedAt, String message) {
        return new Status(status, generatedAt.toString(), message);
    }
}
