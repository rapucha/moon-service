package dev.moonservice.scoringprototype;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

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
        assertEquals("2026-06-29T00:00:00Z", root.path("startsAt").asText());
        assertEquals("2026-07-06T00:00:00Z", root.path("endsAt").asText());
        assertEquals(337, root.path("samplesEvaluated").asInt());

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
        assertEquals("prague-cz-2026-07-01T0300Z", first.path("id").asText());
        assertEquals("2026-07-01T01:15:00Z", first.path("startsAt").asText());
        assertEquals("2026-07-01T03:00:00Z", first.path("peaksAt").asText());
        assertEquals("2026-07-01T03:45:00Z", first.path("endsAt").asText());
        assertEquals(99, first.path("score").asInt());
        assertEquals("high", first.path("confidence").asText());
        assertTrue(first.has("components"));
        assertTrue(first.has("moon"));
        assertTrue(first.has("sun"));
        assertTrue(first.has("weather"));
        assertTrue(first.has("exposureBalance"));
        assertTrue(first.has("reason"));
        assertTrue(first.path("links").path("ics").asText().startsWith("/o/prague-cz-"));
        assertEquals(30, first.path("components").path("moonAltitudeFit").asInt());
        assertEquals(25, first.path("components").path("sunLightFit").asInt());
        assertEquals(15, first.path("components").path("moonIlluminationFit").asInt());
        assertEquals(24, first.path("components").path("weatherFit").asInt());
        assertEquals(5, first.path("components").path("forecastConfidence").asInt());
        assertEquals("golden_hour", first.path("sun").path("lightBucket").asText());
        assertEquals("partly cloudy", first.path("weather").path("summary").asText());
        assertEquals(
                "moon_detail_easy_foreground_supported",
                first.path("exposureBalance").path("label").asText()
        );

        assertTrue(root.path("rejected").isArray());
        assertEquals("local_horizon_not_modelled", root.path("messages").get(0).path("code").asText());
        assertEquals("fixed_fixture", root.path("diagnostics").path("weatherSource").asText());
    }
}
