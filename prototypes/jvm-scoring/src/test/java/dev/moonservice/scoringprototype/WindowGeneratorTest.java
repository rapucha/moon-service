package dev.moonservice.scoringprototype;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Locations;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.window.MoonWindow;
import dev.moonservice.scoringprototype.window.WindowGenerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowGeneratorTest {
    @Test
    void createsNaturalWindowFromAltitudeCrossings() {
        PrototypeConfig config = new PrototypeConfig(
                Locations.PRAGUE,
                LocalDate.parse("2026-06-29"),
                1,
                12.0,
                10
        );
        Instant start = config.start();

        List<MoonWindow> windows = new WindowGenerator().findWindows(config, instant -> {
            double hours = Duration.between(start, instant).toSeconds() / 3600.0;
            return sample(instant, hours * 4.0 - 4.0, -4.0);
        });

        assertEquals(1, windows.size());
        MoonWindow window = windows.getFirst();
        assertEquals("moonrise_low", window.kind());
        assertEquals(start.plus(Duration.ofHours(1)), window.startsAt());
        assertEquals(start.plus(Duration.ofHours(4)), window.endsAt());
        assertTrue(!window.suggested().instant().isBefore(window.startsAt()));
        assertTrue(!window.suggested().instant().isAfter(window.endsAt()));
        assertTrue(window.suggested().moonAltitudeDegrees() >= 1.0);
        assertTrue(window.suggested().moonAltitudeDegrees() <= 6.0);
        assertEquals(window.startsAt(), window.start().instant());
        assertEquals(window.endsAt(), window.end().instant());
        assertTrue(window.pathSamples().stream().anyMatch(sample -> sample.instant().equals(window.startsAt())));
        assertTrue(window.pathSamples().stream().anyMatch(sample -> sample.instant().equals(window.suggested().instant())));
        assertTrue(window.pathSamples().stream().anyMatch(sample -> sample.instant().equals(window.endsAt())));
        assertTrue(window.pathSamples().size() >= 5);
    }

    @Test
    void keepsCarryOverLowMoonWindowAcrossLocalDayBoundaries() {
        PrototypeConfig config = new PrototypeConfig(
                Locations.PRAGUE,
                LocalDate.parse("2026-06-29"),
                2,
                12.0,
                10
        );

        List<MoonWindow> windows = new WindowGenerator().findWindows(
                config,
                instant -> sample(instant, 4.0, -4.0)
        );

        assertEquals(1, windows.size());
        MoonWindow window = windows.getFirst();
        assertEquals(config.start(), window.startsAt());
        assertEquals(config.end(), window.endsAt());
        assertEquals(config.start(), window.passStartsAt());
        assertEquals(config.end(), window.passEndsAt());
    }

    @Test
    void createsContextWindowForHigherVisibleMoon() {
        PrototypeConfig config = new PrototypeConfig(
                Locations.PRAGUE,
                LocalDate.parse("2026-06-29"),
                1,
                90.0,
                10
        );

        List<MoonWindow> windows = new WindowGenerator().findWindows(
                config,
                instant -> sample(instant, 33.0, 2.0)
        );

        assertEquals(1, windows.size());
        MoonWindow window = windows.getFirst();
        assertEquals("moonrise_context", window.kind());
        assertEquals(33.0, window.suggested().moonAltitudeDegrees());
    }

    @Test
    void createsHighContextWindowWhenMoonIsAboveFortyDegrees() {
        PrototypeConfig config = new PrototypeConfig(
                Locations.PRAGUE,
                LocalDate.parse("2026-06-29"),
                1,
                90.0,
                10
        );

        List<MoonWindow> windows = new WindowGenerator().findWindows(
                config,
                instant -> sample(instant, 55.0, 2.0)
        );

        assertEquals(1, windows.size());
        assertEquals("moonrise_high_context", windows.getFirst().kind());
    }

    @Test
    void createsAscendingAndDescendingOpportunitiesInsideOneMoonPass() {
        PrototypeConfig config = new PrototypeConfig(
                Locations.PRAGUE,
                LocalDate.parse("2026-06-29"),
                1,
                90.0,
                10
        );
        Instant start = config.start();

        List<MoonWindow> windows = new WindowGenerator().findWindows(config, instant -> {
            double hours = Duration.between(start, instant).toMinutes() / 60.0;
            double altitude = 4.0 + (hours <= 12.0 ? hours : 24.0 - hours);
            return sample(instant, altitude, -4.0);
        });

        assertEquals(2, windows.size());
        MoonWindow riseWindow = windows.get(0);
        MoonWindow setWindow = windows.get(1);
        assertEquals("moonrise_low", riseWindow.kind());
        assertEquals("moonset_low", setWindow.kind());
        assertEquals(config.start(), riseWindow.passStartsAt());
        assertEquals(config.end(), riseWindow.passEndsAt());
        assertEquals(riseWindow.passId(), setWindow.passId());
        assertEquals(start.plus(Duration.ofHours(12)), riseWindow.endsAt());
        assertEquals(start.plus(Duration.ofHours(12)), setWindow.startsAt());
        assertEquals(riseWindow.passPathSamples(), setWindow.passPathSamples());
        assertEquals(config.start(), riseWindow.passPathSamples().getFirst().instant());
        assertEquals(config.end(), riseWindow.passPathSamples().getLast().instant());
        assertTrue(riseWindow.passPathSamples().stream()
                .anyMatch(sample -> sample.instant().equals(start.plus(Duration.ofHours(18)))));
    }

    @Test
    void addsLightBucketBoundarySamplesToPath() {
        PrototypeConfig config = new PrototypeConfig(
                Locations.PRAGUE,
                LocalDate.parse("2026-06-29"),
                1,
                12.0,
                10
        );
        Instant start = config.start();

        List<MoonWindow> windows = new WindowGenerator().findWindows(config, instant -> {
            double hours = Duration.between(start, instant).toSeconds() / 3600.0;
            return new MoonSample(
                    instant,
                    4.0,
                    120.0,
                    90.0,
                    180.0,
                    null,
                    -15.0 + hours * 2.0,
                    90.0 + hours);
        });

        MoonWindow window = windows.getFirst();
        assertTrue(window.pathSamples().stream()
                .anyMatch(sample -> Math.abs(sample.sunAltitudeDegrees() - -12.0) < 0.01));
        assertTrue(window.pathSamples().stream()
                .anyMatch(sample -> Math.abs(sample.sunAltitudeDegrees() - -6.0) < 0.01));
        assertTrue(window.pathSamples().stream()
                .anyMatch(sample -> Math.abs(sample.sunAltitudeDegrees() - -0.833) < 0.01));
        assertTrue(window.pathSamples().stream()
                .anyMatch(sample -> Math.abs(sample.sunAltitudeDegrees() - 6.0) < 0.01));
    }

    @Test
    void addsRegularPathSamplesForChartShape() {
        PrototypeConfig config = new PrototypeConfig(
                Locations.PRAGUE,
                LocalDate.parse("2026-06-29"),
                1,
                12.0,
                10
        );
        Instant start = config.start();

        List<MoonWindow> windows = new WindowGenerator().findWindows(
                config,
                instant -> sample(instant, 4.0, -4.0)
        );

        MoonWindow window = windows.getFirst();
        assertTrue(window.pathSamples().stream().anyMatch(sample -> sample.instant().equals(start.plus(Duration.ofMinutes(30)))));
        assertTrue(window.pathSamples().stream().anyMatch(sample -> sample.instant().equals(start.plus(Duration.ofMinutes(60)))));
        assertTrue(window.pathSamples().size() >= 49);
    }

    private static MoonSample sample(Instant instant, double moonAltitude, double sunAltitude) {
        return new MoonSample(instant, moonAltitude, 120.0, 90.0, 180.0, null, sunAltitude, 90.0);
    }
}
