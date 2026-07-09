package dev.moonservice.scoringprototype.ephemeris;

import dev.moonservice.scoringprototype.fixture.Locations;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EphemerisSamplerTest {
    @Test
    void matchesObserverOrientedLunarNorthPoleReferenceForPrague() {
        MoonSample sample = new EphemerisSampler().sampleAt(
                Locations.PRAGUE,
                Instant.parse("2026-07-09T13:04:29Z"));

        assertNotNull(sample.northPoleTiltDegrees());
        assertEquals(57.4, sample.northPoleTiltDegrees(), 0.1);
    }
}
