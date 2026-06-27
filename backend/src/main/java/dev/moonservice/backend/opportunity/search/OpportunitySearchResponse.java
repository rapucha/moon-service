package dev.moonservice.backend.opportunity.search;

import java.util.List;
import java.util.Map;

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
        List<Message> messages
) implements OpportunityResponse {
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
            String startsAt,
            String suggestedAt,
            String endsAt,
            String localTimeZone,
            int score,
            String confidence,
            ComponentScores components,
            Moon moon,
            Sun sun,
            Weather weather,
            ExposureBalance exposureBalance,
            String reason,
            Map<String, String> links
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
            double illuminationPercent
    ) {
    }

    public record Sun(
            double altitudeDegrees,
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
            String reason
    ) {
    }

    public record Message(
            String level,
            String code,
            String text
    ) {
    }
}
