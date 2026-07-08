package dev.moonservice.scoringprototype.ephemeris;

import java.time.Instant;

public record MoonSample(
        Instant instant,
        double moonAltitudeDegrees,
        double moonAzimuthDegrees,
        double moonIlluminationPercent,
        double moonPhaseAngleDegrees,
        double sunAltitudeDegrees,
        double sunAzimuthDegrees
) {
    public double moonSunSeparationDegrees() {
        double moonAltitude = Math.toRadians(moonAltitudeDegrees);
        double sunAltitude = Math.toRadians(sunAltitudeDegrees);
        double azimuthDelta = Math.toRadians(moonAzimuthDegrees - sunAzimuthDegrees);
        double cosine = Math.sin(moonAltitude) * Math.sin(sunAltitude)
                + Math.cos(moonAltitude) * Math.cos(sunAltitude) * Math.cos(azimuthDelta);
        return Math.toDegrees(Math.acos(clamp(cosine, -1.0, 1.0)));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
