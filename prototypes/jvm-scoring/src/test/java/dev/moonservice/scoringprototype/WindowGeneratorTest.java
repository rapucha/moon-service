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
    }

    @Test
    void splitsCarryOverLowMoonWindowAtLocalDayBoundaries() {
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

        Instant firstLocalMidnight = LocalDate.parse("2026-06-30")
                .atStartOfDay(Locations.PRAGUE.zoneId())
                .toInstant();

        assertEquals(2, windows.size());
        assertEquals(config.start(), windows.get(0).startsAt());
        assertEquals(firstLocalMidnight, windows.get(0).endsAt());
        assertEquals(firstLocalMidnight, windows.get(1).startsAt());
        assertEquals(config.end(), windows.get(1).endsAt());
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

    private static MoonSample sample(Instant instant, double moonAltitude, double sunAltitude) {
        return new MoonSample(instant, moonAltitude, 120.0, 90.0, sunAltitude);
    }
}
