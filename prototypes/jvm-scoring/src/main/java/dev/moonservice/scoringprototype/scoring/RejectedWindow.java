package dev.moonservice.scoringprototype.scoring;

import dev.moonservice.scoringprototype.window.MoonWindow;

import java.time.Instant;

public record RejectedWindow(
        Instant startsAt,
        Instant endsAt,
        String reasonCode,
        String reason,
        double moonSunSeparationDegrees,
        double moonIlluminationPercent,
        double moonAltitudeDegrees,
        double sunAltitudeDegrees
) {
    public static RejectedWindow visibility(MoonWindow window, String reasonCode) {
        return new RejectedWindow(
                window.startsAt(),
                window.endsAt(),
                reasonCode,
                reasonText(reasonCode),
                window.suggested().moonSunSeparationDegrees(),
                window.suggested().moonIlluminationPercent(),
                window.suggested().moonAltitudeDegrees(),
                window.suggested().sunAltitudeDegrees()
        );
    }

    private static String reasonText(String reasonCode) {
        if (ScoringModel.THIN_CRESCENT_NEAR_CONJUNCTION.equals(reasonCode)) {
            return "The Moon is an extremely thin crescent too close to the Sun for an ordinary visible Moon opportunity.";
        }
        return "Rejected by scoring filters.";
    }
}
