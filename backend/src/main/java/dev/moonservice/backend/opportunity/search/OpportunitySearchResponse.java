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
    }

    public record MoonPass(
            String id,
            String startsAt,
            String endsAt,
            MoonPassPath path
    ) {
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
