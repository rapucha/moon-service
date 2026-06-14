package dev.moonservice.scoringprototype;

import java.time.Instant;

record MoonSample(
        Instant instant,
        double moonAltitudeDegrees,
        double moonAzimuthDegrees,
        double moonIlluminationPercent,
        double sunAltitudeDegrees
) {
}
