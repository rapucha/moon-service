package dev.moonservice.prototype;

import java.time.Instant;

record MoonSample(
        Instant instant,
        double moonAltitudeDegrees,
        double moonAzimuthDegrees,
        double moonIlluminationPercent,
        double sunAltitudeDegrees
) {
}
