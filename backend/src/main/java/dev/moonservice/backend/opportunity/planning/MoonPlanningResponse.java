package dev.moonservice.backend.opportunity.planning;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface MoonPlanningResponse
        permits MoonPlanningResponse.Candidates, MoonPlanningResponse.Status, MoonPlanningResponse.Success {
    String status();

    String generatedAt();

    record Success(
            String status,
            String generatedAt,
            String startsAt,
            String endsAt,
            int planningHorizonDays,
            Location location,
            int appliedPreferenceVersion,
            Map<String, Object> normalizedActiveFilters,
            List<String> ignoredPreferenceFields,
            int ignoredPreferenceFieldCount,
            int additionalIgnoredPreferenceFieldCount,
            PlanningWindow nextPlanningWindow,
            @JsonInclude(JsonInclude.Include.NON_NULL) EmptyReason emptyReason
    ) implements MoonPlanningResponse {
        public Success {
            normalizedActiveFilters = Map.copyOf(normalizedActiveFilters);
            ignoredPreferenceFields = List.copyOf(ignoredPreferenceFields);
            if ((nextPlanningWindow == null) == (emptyReason == null)) {
                throw new IllegalArgumentException(
                        "Exactly one of nextPlanningWindow and emptyReason must be present.");
            }
        }
    }

    record Status(String status, String generatedAt, String message) implements MoonPlanningResponse {
    }

    record Candidates(
            String status,
            String generatedAt,
            List<LocationCandidate> candidates
    ) implements MoonPlanningResponse {
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

    record PlanningWindow(
            String id,
            String windowKind,
            MoonPass moonPass,
            String startsAt,
            String suggestedAt,
            String endsAt,
            String localTimeZone,
            Moon moon,
            Sun sun
    ) {
        public PlanningWindow {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(windowKind, "windowKind");
            Objects.requireNonNull(moonPass, "moonPass");
            Objects.requireNonNull(startsAt, "startsAt");
            Objects.requireNonNull(suggestedAt, "suggestedAt");
            Objects.requireNonNull(endsAt, "endsAt");
            Objects.requireNonNull(localTimeZone, "localTimeZone");
            Objects.requireNonNull(moon, "moon");
            Objects.requireNonNull(sun, "sun");
        }
    }

    record Moon(
            double altitudeDegrees,
            double azimuthDegrees,
            double illuminationPercent,
            double phaseAngleDegrees,
            Double brightLimbTiltDegrees,
            Double northPoleTiltDegrees,
            String phaseName
    ) {
    }

    record Sun(double altitudeDegrees, double azimuthDegrees, String lightBucket) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MoonPass(
            String id,
            String startsAt,
            String endsAt,
            MoonPassPath path,
            List<AzimuthMatchInterval> azimuthMatchIntervals
    ) {
        public MoonPass {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(startsAt, "startsAt");
            Objects.requireNonNull(endsAt, "endsAt");
            Objects.requireNonNull(path, "path");
            if (azimuthMatchIntervals != null) {
                azimuthMatchIntervals = List.copyOf(azimuthMatchIntervals);
            }
        }
    }

    record MoonPassPath(
            MoonPathPoint start,
            MoonPathPoint end,
            List<MoonPathPoint> samples
    ) {
        public MoonPassPath {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            samples = List.copyOf(samples);
        }
    }

    record MoonPathPoint(
            String at,
            double altitudeDegrees,
            double azimuthDegrees,
            double moonPhaseAngleDegrees,
            Double brightLimbTiltDegrees,
            Double northPoleTiltDegrees,
            double sunAltitudeDegrees,
            double sunAzimuthDegrees,
            String lightBucket,
            String role
    ) {
    }

    record AzimuthMatchInterval(String startsAt, String endsAt) {
    }

    record EmptyReason(String code, String text) {
    }
}
