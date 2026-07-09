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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void preservesUndefinedMoonOrientationAsNull() {
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

        JsonNode opportunity = MAPPER.readTree(new ResponseFormatter().format(result))
                .path("opportunities")
                .get(0);
        JsonNode moon = opportunity.path("moon");
        JsonNode suggestedPathPoint = opportunity.path("moonPath").path("suggested");

        assertTrue(moon.has("brightLimbTiltDegrees"));
        assertTrue(moon.path("brightLimbTiltDegrees").isNull());
        assertTrue(moon.has("northPoleTiltDegrees"));
        assertTrue(moon.path("northPoleTiltDegrees").isNull());
        assertTrue(suggestedPathPoint.has("brightLimbTiltDegrees"));
        assertTrue(suggestedPathPoint.path("brightLimbTiltDegrees").isNull());
        assertTrue(suggestedPathPoint.has("northPoleTiltDegrees"));
        assertTrue(suggestedPathPoint.path("northPoleTiltDegrees").isNull());
    }

    @Test
    void preservesEachMoonPathPointOrientation() {
        Instant startsAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant suggestedAt = Instant.parse("2026-01-01T01:00:00Z");
        Instant endsAt = Instant.parse("2026-01-01T02:00:00Z");
        MoonSample start = orientedSample(startsAt, 70.0, 15.0, 90.0);
        MoonSample suggested = orientedSample(suggestedAt, 80.0, 90.0, 180.0);
        MoonSample end = orientedSample(endsAt, 90.0, 225.0, 270.0);
        List<MoonSample> samples = List.of(start, suggested, end);
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
                samples,
                samples);
        PrototypeConfig config = new PrototypeConfig(LOCATION, LocalDate.parse("2026-01-01"), 1, 12.0, 1);
        PrototypeResult result = new PrototypeResult(
                config,
                1,
                List.of(new ScoredWindow(
                        window,
                        WeatherFixture.PRAGUE_PARTLY_CLOUDY,
                        new ComponentScores(1, 1, 1, 1, 1))),
                List.of());

        JsonNode opportunity = MAPPER.readTree(new ResponseFormatter().format(result))
                .path("opportunities")
                .get(0);
        JsonNode passPath = opportunity.path("moonPass").path("path");
        JsonNode moonPath = opportunity.path("moonPath");

        assertMoonOrientation(passPath.path("start"), start);
        assertMoonOrientation(passPath.path("end"), end);
        assertMoonOrientations(passPath.path("samples"), samples);
        assertMoonOrientation(moonPath.path("start"), start);
        assertMoonOrientation(moonPath.path("suggested"), suggested);
        assertMoonOrientation(moonPath.path("end"), end);
        assertMoonOrientations(moonPath.path("samples"), samples);
    }

    private static MoonSample sample(Instant instant, double sunAltitudeDegrees, double sunAzimuthDegrees) {
        return new MoonSample(
                instant,
                0.0,
                0.0,
                100.0,
                180.0,
                null,
                sunAltitudeDegrees,
                sunAzimuthDegrees);
    }

    private static MoonSample orientedSample(
            Instant instant,
            double phaseAngleDegrees,
            double northPoleTiltDegrees,
            double sunAzimuthDegrees
    ) {
        return new MoonSample(
                instant,
                10.0,
                0.0,
                50.0,
                phaseAngleDegrees,
                northPoleTiltDegrees,
                0.0,
                sunAzimuthDegrees);
    }

    private static void assertMoonOrientations(JsonNode nodes, List<MoonSample> samples) {
        assertEquals(samples.size(), nodes.size());
        for (int index = 0; index < samples.size(); index++) {
            assertMoonOrientation(nodes.get(index), samples.get(index));
        }
    }

    private static void assertMoonOrientation(JsonNode node, MoonSample sample) {
        assertEquals(sample.moonPhaseAngleDegrees(), node.path("moonPhaseAngleDegrees").asDouble(), 0.001);
        assertEquals(sample.brightLimbTiltDegrees(), node.path("brightLimbTiltDegrees").asDouble(), 0.001);
        assertEquals(sample.northPoleTiltDegrees(), node.path("northPoleTiltDegrees").asDouble(), 0.001);
    }
}
