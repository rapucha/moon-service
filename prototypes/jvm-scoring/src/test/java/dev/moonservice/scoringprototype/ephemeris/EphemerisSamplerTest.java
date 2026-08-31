package dev.moonservice.scoringprototype.ephemeris;

import dev.moonservice.scoringprototype.fixture.Locations;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EphemerisSamplerTest {
    @Test
    void matchesObserverOrientedLunarNorthPoleReferenceForPrague() {
        MoonSample sample = new EphemerisSampler().sampleAt(
                Locations.PRAGUE,
                Instant.parse("2026-07-09T13:04:29Z"));

        assertNotNull(sample.northPoleTiltDegrees());
        assertEquals(57.4, sample.northPoleTiltDegrees(), 0.1);
    }

    @Test
    void derivesKnownTotalEclipseMaximumGeometryFromPublicVectors() {
        LunarEclipseShadowSample sample = new EphemerisSampler().lunarEclipseShadowAt(
                Locations.PRAGUE,
                Instant.parse("2025-09-07T18:11:41.502Z"));

        assertEquals(-0.1673, sample.centerRightMoonRadii(), 0.0001);
        assertEquals(0.9953, sample.centerUpMoonRadii(), 0.0001);
        assertEquals(2.74475, sample.umbraRadiusMoonRadii(), 0.00001);
        assertEquals(4.70873, sample.penumbraRadiusMoonRadii(), 0.00001);
        assertNotNull(sample.moon().northPoleTiltDegrees());
        assertTrue(Double.isFinite(sample.moon().moonAltitudeDegrees()));
        assertTrue(Double.isFinite(sample.moon().moonAzimuthDegrees()));
    }

    @Test
    void matchesPenumbralPartialAndTotalContactTangencies() {
        EphemerisSampler sampler = new EphemerisSampler();
        List<Contact> contacts = List.of(
                new Contact("2025-09-07T17:30:14.908Z", ShadowRadius.UMBRA, -1.0),
                new Contact("2025-09-07T18:53:08.096Z", ShadowRadius.UMBRA, -1.0),
                new Contact("2026-08-28T02:33:23.787Z", ShadowRadius.UMBRA, 1.0),
                new Contact("2026-08-28T05:52:14.367Z", ShadowRadius.UMBRA, 1.0),
                new Contact("2027-02-20T21:11:52.110Z", ShadowRadius.PENUMBRA, 1.0),
                new Contact("2027-02-21T01:13:36.174Z", ShadowRadius.PENUMBRA, 1.0));

        contacts.forEach(contact -> {
            LunarEclipseShadowSample sample = sampler.lunarEclipseShadowAt(
                    Locations.PRAGUE, Instant.parse(contact.at()));
            double centerDistance = Math.hypot(
                    sample.centerRightMoonRadii(), sample.centerUpMoonRadii());
            double shadowRadius = contact.radius() == ShadowRadius.UMBRA
                    ? sample.umbraRadiusMoonRadii()
                    : sample.penumbraRadiusMoonRadii();
            assertEquals(
                    shadowRadius + contact.moonRadiusOffset(),
                    centerDistance,
                    0.01,
                    contact.at());
        });
    }

    private enum ShadowRadius {
        UMBRA,
        PENUMBRA
    }

    private record Contact(String at, ShadowRadius radius, double moonRadiusOffset) {
    }
}
