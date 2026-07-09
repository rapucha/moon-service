package dev.moonservice.scoringprototype.ephemeris;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MoonSampleTest {
    @Test
    void measuresBrightLimbTiltClockwiseFromLocalZenith() {
        assertEquals(0.0, sample(10.0, 120.0, 20.0, 120.0).brightLimbTiltDegrees(), 1.0e-9);
        assertEquals(90.0, sample(0.0, 0.0, 0.0, 90.0).brightLimbTiltDegrees(), 1.0e-9);
        assertEquals(180.0, sample(20.0, 120.0, 10.0, 120.0).brightLimbTiltDegrees(), 1.0e-9);
        assertEquals(270.0, sample(0.0, 0.0, 0.0, 270.0).brightLimbTiltDegrees(), 1.0e-9);
    }

    @Test
    void omitsBrightLimbTiltWhenProjectedDirectionIsUndefined() {
        assertNull(sample(10.0, 120.0, 10.0, 120.0).brightLimbTiltDegrees());
        assertNull(sample(0.0, 0.0, 0.0, 180.0).brightLimbTiltDegrees());
        assertNull(sample(Double.NaN, 0.0, 0.0, 90.0).brightLimbTiltDegrees());
    }

    private static MoonSample sample(
            double moonAltitudeDegrees,
            double moonAzimuthDegrees,
            double sunAltitudeDegrees,
            double sunAzimuthDegrees
    ) {
        return new MoonSample(
                Instant.EPOCH,
                moonAltitudeDegrees,
                moonAzimuthDegrees,
                50.0,
                90.0,
                sunAltitudeDegrees,
                sunAzimuthDegrees);
    }
}
