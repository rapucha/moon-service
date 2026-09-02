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
            List<MoonEvent> events
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

    sealed interface MoonEvent permits FullMoonEvent, LunarEclipseEvent {
        String id();

        String kind();

        MoonEvent withLinks(Links links);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LunarEclipseEvent(
            String id,
            String kind,
            String subtype,
            String startsAt,
            String maximumAt,
            String endsAt,
            double umbralObscurationPercent,
            List<EclipsePhase> phases,
            List<EclipseShadowSample> shadowSamples,
            MoonPosition moonAtMaximum,
            EventVisibility localVisibility,
            PreferenceAssessment preferenceAssessment,
            Weather weather,
            Links links
    ) implements MoonEvent {
        public LunarEclipseEvent(
                String id,
                String kind,
                String subtype,
                String startsAt,
                String maximumAt,
                String endsAt,
                double umbralObscurationPercent,
                List<EclipsePhase> phases,
                List<EclipseShadowSample> shadowSamples,
                MoonPosition moonAtMaximum,
                EventVisibility localVisibility,
                PreferenceAssessment preferenceAssessment,
                Weather weather
        ) {
            this(id, kind, subtype, startsAt, maximumAt, endsAt, umbralObscurationPercent,
                    phases, shadowSamples, moonAtMaximum, localVisibility,
                    preferenceAssessment, weather, null);
        }

        public LunarEclipseEvent {
            phases = List.copyOf(phases);
            shadowSamples = List.copyOf(shadowSamples);
        }

        @Override
        public LunarEclipseEvent withLinks(Links value) {
            return new LunarEclipseEvent(
                    id, kind, subtype, startsAt, maximumAt, endsAt,
                    umbralObscurationPercent, phases, shadowSamples, moonAtMaximum,
                    localVisibility, preferenceAssessment, weather,
                    java.util.Objects.requireNonNull(value, "links"));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FullMoonEvent(
            String id,
            String kind,
            String peakAt,
            List<FullMoonQualifier> qualifiers,
            LocalViewing localViewing,
            PreferenceAssessment preferenceAssessment,
            Weather weather,
            Links links
    ) implements MoonEvent {
        public FullMoonEvent(
                String id,
                String kind,
                String peakAt,
                List<FullMoonQualifier> qualifiers,
                LocalViewing localViewing,
                PreferenceAssessment preferenceAssessment,
                Weather weather
        ) {
            this(id, kind, peakAt, qualifiers, localViewing, preferenceAssessment, weather, null);
        }

        public FullMoonEvent {
            qualifiers = List.copyOf(qualifiers);
        }

        @Override
        public FullMoonEvent withLinks(Links value) {
            return new FullMoonEvent(
                    id, kind, peakAt, qualifiers, localViewing, preferenceAssessment, weather,
                    java.util.Objects.requireNonNull(value, "links"));
        }
    }

    record Links(String ics) {
    }

    record FullMoonQualifier(
            String kind,
            int definitionVersion,
            double closeness,
            double distanceKilometersAtPeak,
            double perigeeDistanceKilometers,
            double apogeeDistanceKilometers
    ) {
    }

    record EclipsePhase(
            String kind,
            String startsAt,
            String endsAt,
            PhaseVisibility localVisibility
    ) {
    }

    record EclipseShadowSample(
            String at,
            EclipseShadowMoon moon,
            EclipseShadow shadow
    ) {
    }

    record EclipseShadowMoon(
            double altitudeDegrees,
            double azimuthDegrees,
            Double northPoleTiltDegrees
    ) {
    }

    record EclipseShadow(
            double centerRightMoonRadii,
            double centerUpMoonRadii,
            double umbraRadiusMoonRadii,
            double penumbraRadiusMoonRadii
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
            DisplayInterval displayInterval,
            MoonPath moonPath
    ) {
        public EventVisibility {
            intervals = List.copyOf(intervals);
        }
    }

    record LocalViewing(
            List<Interval> intervals,
            Interval selectedInterval,
            DisplayInterval displayInterval,
            MoonPath moonPath
    ) {
        public LocalViewing {
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

    record MoonPath(List<MoonPathSample> samples) {
        public MoonPath {
            samples = List.copyOf(samples);
        }
    }

    record MoonPathSample(
            String at,
            double altitudeDegrees,
            double azimuthDegrees,
            double moonPhaseAngleDegrees,
            Double brightLimbTiltDegrees,
            Double northPoleTiltDegrees,
            double sunAltitudeDegrees,
            double sunAzimuthDegrees,
            String lightBucket,
            @JsonInclude(JsonInclude.Include.NON_NULL) EclipseShadow shadow
    ) {
        MoonPathSample withShadow(EclipseShadow value) {
            return new MoonPathSample(
                    at,
                    altitudeDegrees,
                    azimuthDegrees,
                    moonPhaseAngleDegrees,
                    brightLimbTiltDegrees,
                    northPoleTiltDegrees,
                    sunAltitudeDegrees,
                    sunAzimuthDegrees,
                    lightBucket,
                    value);
        }
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
