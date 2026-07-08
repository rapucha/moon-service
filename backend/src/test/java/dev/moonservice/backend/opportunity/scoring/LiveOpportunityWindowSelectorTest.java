package dev.moonservice.backend.opportunity.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.window.MoonWindow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

class LiveOpportunityWindowSelectorTest {
    private static final Location LOCATION = new Location(
            "prague-cz",
            "real_location",
            "prague-cz",
            "Prague, Czechia",
            50.08804,
            14.42076,
            202,
            "Europe/Prague",
            "CZ");

    @Test
    void dropsCompletedSameDayWindow() {
        Instant now = Instant.parse("2026-06-20T22:00:00Z");
        MoonWindow window = window(
                "2026-06-20T20:00:00Z",
                "2026-06-20T20:30:00Z",
                "2026-06-20T22:00:00Z");

        Optional<MoonWindow> adjusted = new LiveOpportunityWindowSelector(now).adjust(window, this::sampleAt);

        assertTrue(adjusted.isEmpty());
    }

    @Test
    void keepsOngoingSameDayWindowWithRemainingSuggestedTime() {
        Instant now = Instant.parse("2026-06-20T21:00:00Z");
        MoonWindow window = window(
                "2026-06-20T20:00:00Z",
                "2026-06-20T20:30:00Z",
                "2026-06-20T23:00:00Z");

        Optional<MoonWindow> adjusted = new LiveOpportunityWindowSelector(now).adjust(window, this::sampleAt);

        assertTrue(adjusted.isPresent());
        assertEquals(window.startsAt(), adjusted.get().startsAt());
        assertEquals(window.endsAt(), adjusted.get().endsAt());
        assertFalse(adjusted.get().suggested().instant().isBefore(now));
        assertEquals(Instant.parse("2026-06-20T21:10:00Z"), adjusted.get().suggested().instant());
    }

    @Test
    void leavesFutureWindowUnchanged() {
        Instant now = Instant.parse("2026-06-20T19:00:00Z");
        MoonWindow window = window(
                "2026-06-20T20:00:00Z",
                "2026-06-20T20:30:00Z",
                "2026-06-20T23:00:00Z");

        Optional<MoonWindow> adjusted = new LiveOpportunityWindowSelector(now).adjust(window, this::sampleAt);

        assertTrue(adjusted.isPresent());
        assertEquals(window, adjusted.get());
    }

    private MoonSample sampleAt(Instant instant) {
        if (instant.equals(Instant.parse("2026-06-20T21:10:00Z"))) {
            return new MoonSample(instant, 4.0, 120.0, 80.0, 120.0, -2.0, 82.0);
        }
        return new MoonSample(instant, 50.0, 120.0, 80.0, 120.0, -20.0, 62.0);
    }

    private static MoonWindow window(String startsAt, String suggestedAt, String endsAt) {
        MoonSample start = new MoonSample(Instant.parse(startsAt), 3.0, 116.0, 80.0, 120.0, -2.0, 78.0);
        MoonSample suggested = new MoonSample(Instant.parse(suggestedAt), 4.0, 120.0, 80.0, 120.0, -2.0, 82.0);
        MoonSample end = new MoonSample(Instant.parse(endsAt), 5.0, 124.0, 80.0, 120.0, -2.0, 86.0);
        return new MoonWindow(
                LOCATION,
                "moonrise_low",
                start.instant(),
                end.instant(),
                start.instant(),
                start,
                suggested,
                end,
                end.instant(),
                List.of(start, suggested, end),
                List.of(start, suggested, end));
    }
}
