package dev.moonservice.backend;

import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine.PreferenceSearchResult;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.scoringprototype.ephemeris.PhaseOrientationAvailability;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "moon.location.resolver=open-meteo",
                "moon.weather.provider=open-meteo",
                "moon.admin.token=test-admin-token",
                "moon.hosted-alpha.enabled=false",
                "moon.build.revision=test-revision"
        })
@AutoConfigureWebTestClient
@Import(OpportunitySearchFunctionalTest.TestOpenMeteoLocationResolverConfiguration.class)
@Tag("functional")
class PhaseOrientationAvailabilityFunctionalTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WeatherForecastProvider weatherForecastProvider;

    @Test
    void returnsTheEarliestEphemerisOnlyMatchWithoutExtendingWeatherLookup() throws JacksonException {
        clearInvocations(weatherForecastProvider);

        JsonNode response = productPostOk("""
                {"q":"Prague","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":90,"maximum":90},
                  "namedPhases":["full_moon"],
                  "brightLimbOrientationDegrees":[{"start":120,"end":150}]}}
                """);

        assertEquals("no_opportunities_match_preferences", response.at("/emptyReason/code").asString());
        assertEquals("next_match", response.at("/phaseOrientationAvailability/status").asString());
        assertEquals(200, response.at("/phaseOrientationAvailability/lookAheadDays").intValue());
        assertEquals(
                "2026-06-29T00:00:00Z",
                response.at("/phaseOrientationAvailability/nextMatchAt").asString());
        verify(weatherForecastProvider, times(1)).forecastFor(
                any(),
                eq(Instant.parse("2026-06-28T22:00:00Z")),
                eq(Instant.parse("2026-07-05T22:00:00Z")),
                eq(7));
    }

    @Test
    void unrelatedHardPreferencesDoNotAlterTheDiagnostic() throws JacksonException {
        JsonNode altitudeOnly = productPostOk("""
                {"q":"Prague","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":90,"maximum":90},
                  "namedPhases":["full_moon"],
                  "brightLimbOrientationDegrees":[{"start":120,"end":150}]}}
                """);
        JsonNode withOtherFilters = productPostOk("""
                {"q":"Prague","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":90,"maximum":90},
                  "azimuthDegrees":{"included":{"start":10,"end":350}},
                  "time":{"mode":"light_bucket","buckets":["daylight"]},
                  "namedPhases":["full_moon"],
                  "brightLimbOrientationDegrees":[{"start":120,"end":150}]}}
                """);

        assertEquals(
                altitudeOnly.path("phaseOrientationAvailability"),
                withOtherFilters.path("phaseOrientationAvailability"));
    }

    @Test
    void omitsTheDiagnosticForValidMultipleRangesAndIncompleteFilterPairs() throws JacksonException {
        JsonNode multipleRanges = productPostOk("""
                {"q":"Prague","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":90,"maximum":90},
                  "namedPhases":["full_moon"],
                  "brightLimbOrientationDegrees":[
                    {"start":120,"end":135},{"start":135,"end":150}]}}
                """);
        JsonNode noPhase = productPostOk("""
                {"q":"Prague","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":90,"maximum":90},
                  "brightLimbOrientationDegrees":[{"start":120,"end":150}]}}
                """);
        JsonNode noOrientation = productPostOk("""
                {"q":"Prague","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":90,"maximum":90},
                  "namedPhases":["full_moon"]}}
                """);

        assertEquals("no_opportunities_match_preferences", multipleRanges.at("/emptyReason/code").asString());
        assertEquals("no_opportunities_match_preferences", noPhase.at("/emptyReason/code").asString());
        assertEquals("no_opportunities_match_preferences", noOrientation.at("/emptyReason/code").asString());
        assertFalse(multipleRanges.has("phaseOrientationAvailability"));
        assertFalse(noPhase.has("phaseOrientationAvailability"));
        assertFalse(noOrientation.has("phaseOrientationAvailability"));
    }

    @Test
    void omitsTheDiagnosticWhenTheResponseHasOpportunities() throws JacksonException {
        JsonNode response = productPostOk("{\"q\":\"Prague\"}");

        assertTrue(response.path("opportunities").size() > 0);
        assertFalse(response.has("phaseOrientationAvailability"));
    }

    @Test
    void serializesNotFoundWithoutANextMatchInstant() {
        PreferenceSearchResult result = preferenceResult(
                true,
                1,
                new PhaseOrientationAvailability.Result(200, null));

        JsonNode response = MAPPER.valueToTree(
                OpportunitySearchResponse.withPreferences(result, List.of(), 0));

        assertEquals("not_found", response.at("/phaseOrientationAvailability/status").asString());
        assertEquals(200, response.at("/phaseOrientationAvailability/lookAheadDays").intValue());
        assertFalse(response.path("phaseOrientationAvailability").has("nextMatchAt"));
    }

    @Test
    void omitsAnAvailabilityValueWithoutTheFilteredEmptyReason() {
        PreferenceSearchResult result = preferenceResult(
                false,
                0,
                new PhaseOrientationAvailability.Result(
                        200,
                        Instant.parse("2026-06-29T00:00:00Z")));

        JsonNode response = MAPPER.valueToTree(
                OpportunitySearchResponse.withPreferences(result, List.of(), 0));

        assertFalse(response.has("emptyReason"));
        assertFalse(response.has("phaseOrientationAvailability"));
    }

    private JsonNode productPostOk(String body) throws JacksonException {
        String response = webTestClient.post().uri("/api/opportunities")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return MAPPER.readTree(response);
    }

    private static PreferenceSearchResult preferenceResult(
            boolean removedAll,
            int excludedSamples,
            PhaseOrientationAvailability.Result availability
    ) {
        OpportunitySearchResponse response = new OpportunitySearchResponse(
                "ok",
                "2026-06-29T00:00:00Z",
                new OpportunitySearchResponse.Location(
                        "prague-cz", "real_location", "Prague, Czechia",
                        50.08804, 14.42076, 202, "Europe/Prague", "CZ"),
                7,
                "2026-06-28T22:00:00Z",
                "2026-07-05T22:00:00Z",
                1,
                90.0,
                List.of(),
                List.of(),
                List.of());
        return new PreferenceSearchResult(
                response,
                1,
                Map.of(
                        "namedPhases", List.of("full_moon"),
                        "brightLimbOrientationDegrees", List.of(Map.of("start", 120.0, "end", 150.0))),
                excludedSamples,
                removedAll,
                Map.of(),
                availability);
    }
}
