package dev.moonservice.scoringprototype;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void returnsApiShapedPragueFixtureResponse() throws Exception {
        PrototypeConfig config = RequestConfigReader.read(Path.of("fixtures/prague-preview-request.json"));

        String json = MoonScoringPrototype.run(config);
        JsonNode root = mapper.readTree(json);

        assertEquals("ok", root.path("status").asText());
        assertEquals("jvm_maven_ephemeris_scoring_fixture", root.path("prototype").asText());
        assertEquals("Astronomy Engine 2.1.19 via JitPack", root.path("ephemerisSource").asText());
        assertEquals(7, root.path("forecastHorizonDays").asInt());
        assertEquals("2026-06-28T22:00:00Z", root.path("startsAt").asText());
        assertEquals("2026-07-05T22:00:00Z", root.path("endsAt").asText());
        assertTrue(root.path("candidateWindowsEvaluated").asInt() > 0);
        assertFalse(root.has("sampleStepMinutes"));
        assertFalse(root.has("samplesEvaluated"));
        assertFalse(root.has("minScore"));

        JsonNode location = root.path("location");
        assertEquals("real_location", location.path("kind").asText());
        assertEquals("openmeteo:prague-cz", location.path("id").asText());
        assertEquals("Europe/Prague", location.path("timezone").asText());
        assertEquals("CZ", location.path("countryCode").asText());

        JsonNode opportunities = root.path("opportunities");
        assertTrue(opportunities.isArray());
        assertFalse(opportunities.isEmpty());
        assertTrue(opportunities.size() <= 5);

        JsonNode first = opportunities.get(0);
        assertTrue(first.path("id").asText().startsWith("prague-cz-"));
        assertTrue(first.has("windowKind"));
        assertTrue(first.has("suggestedAt"));
        assertFalse(first.has("peaksAt"));
        assertSuggestedInsideWindow(first);
        assertTrue(first.path("score").asInt() >= 0);
        assertFalse(first.path("confidence").asText().isBlank());
        assertTrue(first.has("components"));
        assertTrue(first.has("moon"));
        assertTrue(first.has("sun"));
        assertTrue(first.has("weather"));
        assertTrue(first.has("exposureBalance"));
        assertTrue(first.has("reason"));
        assertTrue(first.path("links").path("ics").asText().startsWith("/o/prague-cz-"));
        assertEquals(24, first.path("components").path("weatherFit").asInt());
        assertEquals(5, first.path("components").path("forecastConfidence").asInt());
        assertFalse(first.path("sun").path("lightBucket").asText().isBlank());
        assertEquals("hourly", first.path("weather").path("sourceResolution").asText());
        assertEquals("partly_cloudy", first.path("weather").path("segmentKind").asText());
        assertEquals("partly cloudy", first.path("weather").path("summary").asText());

        assertTrue(root.path("rejected").isArray());
        assertEquals("local_horizon_not_modelled", root.path("messages").get(0).path("code").asText());
        assertEquals("fixed_fixture", root.path("diagnostics").path("weatherSource").asText());
        assertEquals("hourly_fixture", root.path("diagnostics").path("weatherResolution").asText());
    }

    private static void assertSuggestedInsideWindow(JsonNode opportunity) {
        Instant startsAt = Instant.parse(opportunity.path("startsAt").asText());
        Instant suggestedAt = Instant.parse(opportunity.path("suggestedAt").asText());
        Instant endsAt = Instant.parse(opportunity.path("endsAt").asText());

        assertFalse(suggestedAt.isBefore(startsAt));
        assertFalse(suggestedAt.isAfter(endsAt));
    }
}
