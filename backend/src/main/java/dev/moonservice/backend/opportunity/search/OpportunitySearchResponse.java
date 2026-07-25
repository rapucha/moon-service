package dev.moonservice.backend.opportunity.search;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpportunitySearchResponse(
        String status,
        String generatedAt,
        Location location,
        int forecastHorizonDays,
        String startsAt,
        String endsAt,
        int candidateWindowsEvaluated,
        double maxMoonAltitudeDegrees,
        List<Opportunity> opportunities,
        List<RejectedWindow> rejected,
        List<Message> messages,
        Integer appliedPreferenceVersion,
        Map<String, Object> normalizedActiveFilters,
        Integer excludedSampleCount,
        List<String> ignoredPreferenceFields,
        Integer ignoredPreferenceFieldCount,
        Integer additionalIgnoredPreferenceFieldCount,
        EmptyReason emptyReason
) implements OpportunityResponse {
    public OpportunitySearchResponse(
            String status,
            String generatedAt,
            Location location,
            int forecastHorizonDays,
            String startsAt,
            String endsAt,
            int candidateWindowsEvaluated,
            double maxMoonAltitudeDegrees,
            List<Opportunity> opportunities,
            List<RejectedWindow> rejected,
            List<Message> messages
    ) {
        this(
                status, generatedAt, location, forecastHorizonDays, startsAt, endsAt,
                candidateWindowsEvaluated, maxMoonAltitudeDegrees, opportunities, rejected, messages,
                null, null, null, null, null, null, null);
    }

    public static OpportunitySearchResponse withPreferences(
            OpportunitySearchEngine.PreferenceSearchResult result,
            List<String> ignoredPreferenceFields,
            int ignoredPreferenceFieldCount
    ) {
        OpportunitySearchResponse response = result.response();
        boolean hasAzimuthFilter = result.normalizedActiveFilters().containsKey("azimuthDegrees");
        List<Opportunity> opportunities = hasAzimuthFilter
                ? response.opportunities().stream()
                        .map(opportunity -> opportunity.withMoonPass(opportunity.moonPass().withAzimuthIntervals(
                                result.azimuthMatchIntervals().get(opportunity.moonPass().id()))))
                        .toList()
                : response.opportunities();
        EmptyReason emptyReason = result.normalizedActiveFilters().isEmpty()
                || result.excludedSampleCount() == 0
                || !result.preferencesRemovedAllLiveCandidates()
                || !opportunities.isEmpty()
                ? null
                : new EmptyReason(
                        "no_opportunities_match_preferences",
                        "No opportunity matched the active preferences.");
        return new OpportunitySearchResponse(
                response.status(),
                response.generatedAt(),
                response.location(),
                response.forecastHorizonDays(),
                response.startsAt(),
                response.endsAt(),
                response.candidateWindowsEvaluated(),
                response.maxMoonAltitudeDegrees(),
                opportunities,
                response.rejected(),
                response.messages(),
                result.appliedPreferenceVersion(),
                result.normalizedActiveFilters(),
                result.excludedSampleCount(),
                List.copyOf(ignoredPreferenceFields),
                ignoredPreferenceFieldCount,
                Math.max(0, ignoredPreferenceFieldCount - ignoredPreferenceFields.size()),
                emptyReason);
    }

    public record Location(
            String id,
            String kind,
            String displayName,
            double latitude,
            double longitude,
            int elevationMeters,
            String timezone,
            String countryCode
    ) {
    }

    public record Opportunity(
            String id,
            String windowKind,
            MoonPass moonPass,
            String startsAt,
            String suggestedAt,
            String endsAt,
            String localTimeZone,
            int score,
            String confidence,
            ComponentScores components,
            Moon moon,
            MoonPath moonPath,
            Sun sun,
            Weather weather,
            ExposureBalance exposureBalance,
            String reason,
            Map<String, String> links
    ) {
        private Opportunity withMoonPass(MoonPass value) {
            return new Opportunity(
                    id, windowKind, value, startsAt, suggestedAt, endsAt, localTimeZone,
                    score, confidence, components, moon, moonPath, sun, weather,
                    exposureBalance, reason, links);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MoonPass(
            String id,
            String startsAt,
            String endsAt,
            MoonPassPath path,
            List<AzimuthMatchInterval> azimuthMatchIntervals
    ) {
        public MoonPass(String id, String startsAt, String endsAt, MoonPassPath path) {
            this(id, startsAt, endsAt, path, null);
        }

        private MoonPass withAzimuthIntervals(
                List<OpportunitySearchEngine.AzimuthMatchInterval> intervals
        ) {
            if (intervals == null) {
                throw new IllegalStateException("Preference result has no azimuth mask for returned Moon pass.");
            }
            return new MoonPass(
                    id,
                    startsAt,
                    endsAt,
                    path,
                    intervals.stream()
                            .map(interval -> new AzimuthMatchInterval(
                                    interval.startsAt().toString(),
                                    interval.endsAt().toString()))
                            .toList());
        }
    }

    public record AzimuthMatchInterval(String startsAt, String endsAt) {
    }

    public record EmptyReason(String code, String text) {
    }

    public record MoonPassPath(
            MoonPathPoint start,
            MoonPathPoint end,
            List<MoonPathPoint> samples
    ) {
    }

    public record ComponentScores(
            int moonAltitudeFit,
            int sunLightFit,
            int moonIlluminationFit,
            int weatherFit,
            int forecastConfidence
    ) {
    }

    public record Moon(
            double altitudeDegrees,
            double azimuthDegrees,
            double illuminationPercent,
            double phaseAngleDegrees,
            Double brightLimbTiltDegrees,
            Double northPoleTiltDegrees,
            String phaseName
    ) {
    }

    public record MoonPath(
            MoonPathPoint start,
            MoonPathPoint suggested,
            MoonPathPoint end,
            List<MoonPathPoint> samples
    ) {
    }

    public record MoonPathPoint(
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

    public record Sun(
            double altitudeDegrees,
            double azimuthDegrees,
            String lightBucket
    ) {
    }

    public record Weather(
            String sourceResolution,
            String segmentKind,
            int cloudCoverMeanPercent,
            int cloudCoverMaxPercent,
            int lowCloudCoverMaxPercent,
            int midCloudCoverMaxPercent,
            int highCloudCoverMaxPercent,
            int precipitationProbabilityMaxPercent,
            double precipitationMm,
            int visibilityMinMeters,
            int weatherCode,
            String summary
    ) {
    }

    public record ExposureBalance(
            String label,
            String text
    ) {
    }

    public record RejectedWindow(
            String startsAt,
            String endsAt,
            String reasonCode,
            String reason,
            double moonSunSeparationDegrees,
            double moonIlluminationPercent,
            double moonAltitudeDegrees,
            double sunAltitudeDegrees
    ) {
    }

    public record Message(
            String level,
            String code,
            String text
    ) {
    }
}
