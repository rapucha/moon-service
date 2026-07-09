package dev.moonservice.scoringprototype.output;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.scoring.ComponentScores;
import dev.moonservice.scoringprototype.scoring.ScoredWindow;
import dev.moonservice.scoringprototype.service.PrototypeResult;
import dev.moonservice.scoringprototype.window.MoonWindow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseFormatterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Location LOCATION = new Location(
            "test-location",
            "real_location",
            "test:location",
            "Test Location",
            0.0,
            0.0,
            0.0,
            "UTC",
            "ZZ");

    @Test
    void preservesUndefinedBrightLimbTiltAsNull() {
        Instant startsAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant suggestedAt = Instant.parse("2026-01-01T01:00:00Z");
        Instant endsAt = Instant.parse("2026-01-01T02:00:00Z");
        MoonSample start = sample(startsAt, 5.0, 90.0);
        MoonSample suggested = sample(suggestedAt, 0.0, 180.0);
        MoonSample end = sample(endsAt, 5.0, 90.0);
        MoonWindow window = new MoonWindow(
                LOCATION,
                "moonrise_low",
                startsAt,
                endsAt,
                startsAt,
                start,
                suggested,
                end,
                endsAt,
                List.of(start, suggested, end),
                List.of(start, suggested, end));
        PrototypeConfig config = new PrototypeConfig(LOCATION, LocalDate.parse("2026-01-01"), 1, 12.0, 1);
        PrototypeResult result = new PrototypeResult(
                config,
                1,
                List.of(new ScoredWindow(
                        window,
                        WeatherFixture.PRAGUE_PARTLY_CLOUDY,
                        new ComponentScores(1, 1, 1, 1, 1))),
                List.of());

        JsonNode moon = MAPPER.readTree(new ResponseFormatter().format(result))
                .path("opportunities")
                .get(0)
                .path("moon");

        assertTrue(moon.has("brightLimbTiltDegrees"));
        assertTrue(moon.path("brightLimbTiltDegrees").isNull());
    }

    private static MoonSample sample(Instant instant, double sunAltitudeDegrees, double sunAzimuthDegrees) {
        return new MoonSample(
                instant,
                0.0,
                0.0,
                100.0,
                180.0,
                sunAltitudeDegrees,
                sunAzimuthDegrees);
    }
}
