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
    private static final double BRIGHT_LIMB_PROJECTION_EPSILON = 1.0e-12;

    public double moonSunSeparationDegrees() {
        double moonAltitude = Math.toRadians(moonAltitudeDegrees);
        double sunAltitude = Math.toRadians(sunAltitudeDegrees);
        double azimuthDelta = Math.toRadians(moonAzimuthDegrees - sunAzimuthDegrees);
        double cosine = Math.sin(moonAltitude) * Math.sin(sunAltitude)
                + Math.cos(moonAltitude) * Math.cos(sunAltitude) * Math.cos(azimuthDelta);
        return Math.toDegrees(Math.acos(clamp(cosine, -1.0, 1.0)));
    }

    public Double brightLimbTiltDegrees() {
        if (!Double.isFinite(moonAltitudeDegrees)
                || !Double.isFinite(moonAzimuthDegrees)
                || !Double.isFinite(sunAltitudeDegrees)
                || !Double.isFinite(sunAzimuthDegrees)) {
            return null;
        }

        double moonAltitude = Math.toRadians(moonAltitudeDegrees);
        double sunAltitude = Math.toRadians(sunAltitudeDegrees);
        double azimuthDelta = Math.toRadians(sunAzimuthDegrees - moonAzimuthDegrees);
        double rightward = Math.cos(sunAltitude) * Math.sin(azimuthDelta);
        double upward = Math.sin(sunAltitude) * Math.cos(moonAltitude)
                - Math.cos(sunAltitude) * Math.sin(moonAltitude) * Math.cos(azimuthDelta);
        if (Math.hypot(rightward, upward) <= BRIGHT_LIMB_PROJECTION_EPSILON) {
            return null;
        }

        return normalizeDegrees(Math.toDegrees(Math.atan2(rightward, upward)));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double normalizeDegrees(double value) {
        return ((value % 360.0) + 360.0) % 360.0;
    }
}
