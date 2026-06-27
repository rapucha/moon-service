package dev.moonservice.scoringprototype;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.moonservice.scoringprototype.cli.MoonScoringPrototype;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.input.RequestConfigReader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PragueContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsApiShapedPragueFixtureResponse() {
        PrototypeConfig config = RequestConfigReader.read(Path.of("fixtures/prague-preview-request.json"));

        String json = MoonScoringPrototype.run(config);
        JsonNode root = mapper.readTree(json);

        assertEquals("ok", root.path("status").asString());
        assertEquals("jvm_maven_ephemeris_scoring_fixture", root.path("prototype").asString());
        assertEquals("Astronomy Engine 2.1.19 via JitPack", root.path("ephemerisSource").asString());
        assertEquals(7, root.path("forecastHorizonDays").asInt());
        assertEquals("2026-06-28T22:00:00Z", root.path("startsAt").asString());
        assertEquals("2026-07-05T22:00:00Z", root.path("endsAt").asString());
        assertTrue(root.path("candidateWindowsEvaluated").asInt() > 0);
        assertFalse(root.has("sampleStepMinutes"));
        assertFalse(root.has("samplesEvaluated"));
        assertFalse(root.has("minScore"));

        JsonNode location = root.path("location");
        assertEquals("real_location", location.path("kind").asString());
        assertEquals("openmeteo:prague-cz", location.path("id").asString());
        assertEquals("Europe/Prague", location.path("timezone").asString());
        assertEquals("CZ", location.path("countryCode").asString());

        JsonNode opportunities = root.path("opportunities");
        assertTrue(opportunities.isArray());
        assertFalse(opportunities.isEmpty());
        assertTrue(opportunities.size() <= 5);

        JsonNode first = opportunities.get(0);
        assertTrue(first.path("id").asString().startsWith("prague-cz-"));
        assertTrue(first.has("windowKind"));
        assertTrue(first.has("suggestedAt"));
        assertFalse(first.has("peaksAt"));
        assertSuggestedInsideWindow(first);
        assertTrue(first.path("score").asInt() >= 0);
        assertFalse(first.path("confidence").asString().isBlank());
        assertTrue(first.has("components"));
        assertTrue(first.has("moon"));
        assertTrue(first.has("sun"));
        assertTrue(first.has("weather"));
        assertTrue(first.has("exposureBalance"));
        assertTrue(first.has("reason"));
        assertTrue(first.path("links").path("ics").asString().startsWith("/o/prague-cz-"));
        assertEquals(24, first.path("components").path("weatherFit").asInt());
        assertEquals(5, first.path("components").path("forecastConfidence").asInt());
        assertFalse(first.path("sun").path("lightBucket").asString().isBlank());
        assertEquals("hourly", first.path("weather").path("sourceResolution").asString());
        assertEquals("partly_cloudy", first.path("weather").path("segmentKind").asString());
        assertEquals("partly cloudy", first.path("weather").path("summary").asString());

        assertTrue(root.path("rejected").isArray());
        assertEquals("local_horizon_not_modelled", root.path("messages").get(0).path("code").asString());
        assertEquals("fixed_fixture", root.path("diagnostics").path("weatherSource").asString());
        assertEquals("hourly_fixture", root.path("diagnostics").path("weatherResolution").asString());
    }

    private static void assertSuggestedInsideWindow(JsonNode opportunity) {
        Instant startsAt = Instant.parse(opportunity.path("startsAt").asString());
        Instant suggestedAt = Instant.parse(opportunity.path("suggestedAt").asString());
        Instant endsAt = Instant.parse(opportunity.path("endsAt").asString());

        assertFalse(suggestedAt.isBefore(startsAt));
        assertFalse(suggestedAt.isAfter(endsAt));
    }
}
