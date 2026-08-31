package dev.moonservice.backend.events;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

public sealed interface MoonEventResponse
        permits MoonEventResponse.Candidates, MoonEventResponse.Status, MoonEventResponse.Success {
    String status();

    String generatedAt();

    record Success(
            String status,
            String generatedAt,
            String startsAt,
            String endsAt,
            Location location,
            int appliedPreferenceVersion,
            Map<String, Object> normalizedActiveFilters,
            List<String> ignoredPreferenceFields,
            int ignoredPreferenceFieldCount,
            int additionalIgnoredPreferenceFieldCount,
            List<LunarEclipseEvent> events
    ) implements MoonEventResponse {
        public Success {
            normalizedActiveFilters = Map.copyOf(normalizedActiveFilters);
            ignoredPreferenceFields = List.copyOf(ignoredPreferenceFields);
            events = List.copyOf(events);
        }
    }

    record Status(String status, String generatedAt, String message) implements MoonEventResponse {
    }

    record Candidates(
            String status,
            String generatedAt,
            List<LocationCandidate> candidates
    ) implements MoonEventResponse {
        public Candidates {
            candidates = List.copyOf(candidates);
        }
    }

    record Location(
            String id,
            String kind,
            String displayName,
            String timezone,
            String countryCode
    ) {
    }

    record LocationCandidate(
            String kind,
            String id,
            String displayName,
            String countryCode,
            String timezone
    ) {
    }

    record LunarEclipseEvent(
            String id,
            String kind,
            String subtype,
            String startsAt,
            String maximumAt,
            String endsAt,
            double umbralObscurationPercent,
            List<EclipsePhase> phases,
            MoonPosition moonAtMaximum,
            EventVisibility localVisibility,
            PreferenceAssessment preferenceAssessment,
            Weather weather
    ) {
        public LunarEclipseEvent {
            phases = List.copyOf(phases);
        }
    }

    record EclipsePhase(
            String kind,
            String startsAt,
            String endsAt,
            PhaseVisibility localVisibility
    ) {
    }

    record MoonPosition(double altitudeDegrees, double azimuthDegrees) {
    }

    record SunPosition(double altitudeDegrees, String lightBucket) {
    }

    record Interval(String startsAt, String endsAt) {
    }

    record PhaseVisibility(String status, List<Interval> intervals) {
        public PhaseVisibility {
            intervals = List.copyOf(intervals);
        }
    }

    record EventVisibility(
            String status,
            List<Interval> intervals,
            Interval selectedInterval,
            DisplayInterval displayInterval
    ) {
        public EventVisibility {
            intervals = List.copyOf(intervals);
        }
    }

    record DisplayInterval(
            String startsAt,
            String suggestedAt,
            String endsAt,
            MoonPosition moon,
            SunPosition sun
    ) {
    }

    record PreferenceAssessment(String overall, List<FilterAssessment> filters) {
        public PreferenceAssessment {
            filters = List.copyOf(filters);
        }
    }

    record FilterAssessment(String filter, String status) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Weather(
            String status,
            String forecastHourStartsAt,
            String summary,
            Integer cloudCoverPercent,
            Integer precipitationProbabilityPercent
    ) {
        static Weather outsideForecastHorizon() {
            return new Weather("outside_forecast_horizon", null, null, null, null);
        }

        static Weather temporarilyUnavailable() {
            return new Weather("temporarily_unavailable", null, null, null, null);
        }
    }
}
