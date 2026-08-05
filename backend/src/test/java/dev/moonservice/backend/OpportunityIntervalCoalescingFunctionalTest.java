package dev.moonservice.backend;

import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.openmeteo.TestOpenMeteoLocationResolver;
import dev.moonservice.backend.weather.TestWeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "moon.location.resolver=open-meteo",
                "moon.weather.provider=open-meteo",
                "moon.hosted-alpha.enabled=false",
                "moon.build.revision=coalescing-test"
        })
@AutoConfigureWebTestClient(timeout = "30s")
@Tag("functional")
class OpportunityIntervalCoalescingFunctionalTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant CAPTURED_AT = Instant.parse("2026-07-30T00:00:00Z");
    private static final Instant EXPECTED_START_LOWER = Instant.parse("2026-08-05T03:31:00Z");
    private static final Instant EXPECTED_START_UPPER = Instant.parse("2026-08-05T03:34:00Z");
    private static final Instant FORMER_BOUNDARY = Instant.parse("2026-08-05T03:57:10Z");
    private static final Instant EXPECTED_END_UPPER = Instant.parse("2026-08-05T03:59:00Z");
    private static final String PREFERENCES = """
            "preferences":{"version":1,
              "time":{"mode":"light_bucket","buckets":["daylight","golden_hour"]},
              "namedPhases":["waxing_crescent","first_quarter","waxing_gibbous",
                "full_moon","waning_gibbous","last_quarter","waning_crescent"],
              "brightLimbOrientationDegrees":[{"start":247.5,"end":292.5}]}
            """;

    @Autowired
    private WebTestClient webTestClient;

    @TestConfiguration
    static class CoalescingTestConfiguration {
        @Bean
        @Primary
        LocationResolver coalescingLocationResolver() {
            return new TestOpenMeteoLocationResolver();
        }

        @Bean
        @Primary
        WeatherForecastProvider coalescingWeatherProvider() {
            return new TestWeatherForecastProvider();
        }

        @Bean
        @Primary
        Clock coalescingClock() {
            return Clock.fixed(CAPTURED_AT, ZoneOffset.UTC);
        }
    }

    @Test
    void ordinarySearchReturnsOneContinuousPeakBoundaryMatch() throws JacksonException {
        JsonNode response = post("/api/opportunities", """
                {"locationId":"prague-cz",%s}
                """.formatted(PREFERENCES));
        JsonNode affected = affectedOpportunity(response.path("opportunities"));
        String passId = affected.at("/moonPass/id").asString();

        assertEquals(1, response.path("opportunities").valueStream()
                .filter(opportunity -> opportunity.at("/moonPass/id").asString().equals(passId))
                .count());
        assertCombinedBounds(affected);
        assertEquals(affected.path("startsAt").asString(), affected.at("/moonPath/start/at").asString());
        assertEquals(affected.path("endsAt").asString(), affected.at("/moonPath/end/at").asString());
        assertEquals(affected.path("suggestedAt").asString(), affected.at("/moonPath/suggested/at").asString());
        assertTrue(affected.path("score").isNumber());
        assertTrue(affected.path("weather").isObject());
    }

    @Test
    void planningSearchReturnsTheCompleteCombinedMatch() throws JacksonException {
        JsonNode ordinary = affectedOpportunity(post("/api/opportunities", """
                {"locationId":"prague-cz",%s}
                """.formatted(PREFERENCES)).path("opportunities"));
        JsonNode planning = post("/api/opportunities/planning", """
                {"locationId":"prague-cz",%s}
                """.formatted(PREFERENCES)).path("nextPlanningWindow");

        assertTrue(planning.isObject());
        assertCombinedBounds(planning);
        assertEquals(ordinary.path("startsAt"), planning.path("startsAt"));
        assertEquals(ordinary.path("endsAt"), planning.path("endsAt"));
        assertEquals(ordinary.path("suggestedAt"), planning.path("suggestedAt"));
    }

    private JsonNode post(String path, String body) throws JacksonException {
        String response = webTestClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertNotNull(response);
        return MAPPER.readTree(response);
    }

    private static JsonNode affectedOpportunity(JsonNode opportunities) {
        return opportunities.valueStream()
                .filter(opportunity -> between(
                        Instant.parse(opportunity.path("startsAt").asString()),
                        EXPECTED_START_LOWER,
                        EXPECTED_START_UPPER))
                .findFirst()
                .orElseThrow();
    }

    private static void assertCombinedBounds(JsonNode window) {
        Instant startsAt = Instant.parse(window.path("startsAt").asString());
        Instant suggestedAt = Instant.parse(window.path("suggestedAt").asString());
        Instant endsAt = Instant.parse(window.path("endsAt").asString());
        assertTrue(between(startsAt, EXPECTED_START_LOWER, EXPECTED_START_UPPER));
        assertTrue(endsAt.isAfter(FORMER_BOUNDARY));
        assertTrue(endsAt.isBefore(EXPECTED_END_UPPER));
        assertFalse(suggestedAt.isBefore(startsAt));
        assertFalse(suggestedAt.isAfter(endsAt));
    }

    private static boolean between(Instant value, Instant lower, Instant upper) {
        return !value.isBefore(lower) && value.isBefore(upper);
    }
}
