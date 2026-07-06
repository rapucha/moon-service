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
}
