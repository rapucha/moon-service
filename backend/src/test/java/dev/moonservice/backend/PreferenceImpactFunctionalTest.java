package dev.moonservice.backend;

import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine.PreferenceSearchResult;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.scoringprototype.window.PreferenceImpactAnalysis;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
class PreferenceImpactFunctionalTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int LOOK_AHEAD_DAYS = 200;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WeatherForecastProvider weatherForecastProvider;

    @Test
    void returnsIndependentPreferenceImpactWithoutExtendingWeatherLookup() throws JacksonException {
        clearInvocations(weatherForecastProvider);

        JsonNode response = productPostOk("""
                {"q":"Prague","preferences":{"version":1,
                  "altitudeDegrees":{"minimum":0,"maximum":90},
                  "azimuthDegrees":{"included":{"start":0,"end":359.999}},
                  "time":{"mode":"light_bucket","buckets":[
                    "daylight","golden_hour","civil_twilight","nautical_twilight","night"]},
                  "namedPhases":[
                    "new_moon","waxing_crescent","first_quarter","waxing_gibbous",
                    "full_moon","waning_gibbous","last_quarter","waning_crescent"],
                  "brightLimbOrientationDegrees":[{"start":0,"end":359.999}]}}
                """);

        assertTrue(response.path("opportunities").size() > 0);
        assertTrue(response.path("excludedSampleCount").isNumber());
        assertTrue(response.at("/preferenceImpact/unfilteredOpportunityCount").intValue() > 0);
        assertEquals(5, response.at("/preferenceImpact/filters").size());
        Set<String> filterKeys = new HashSet<>();
        for (JsonNode impact : response.at("/preferenceImpact/filters")) {
            filterKeys.add(impact.path("filter").asString());
            assertTrue(impact.path("matchingOpportunityCount").intValue()
                    <= response.at("/preferenceImpact/unfilteredOpportunityCount").intValue());
            assertEquals("next_match", impact.path("status").asString());
            assertEquals(LOOK_AHEAD_DAYS,
                    impact.path("lookAheadDays").intValue());
            assertTrue(impact.path("nextMatchAt").isString());
        }
        assertEquals(Set.of(
                "altitudeDegrees", "azimuthDegrees", "time",
                "namedPhases", "brightLimbOrientationDegrees"), filterKeys);
        assertFalse(response.has("phaseOrientationAvailability"));
        verify(weatherForecastProvider, times(1)).forecastFor(
                any(),
                eq(Instant.parse("2026-06-28T22:00:00Z")),
                eq(Instant.parse("2026-07-05T22:00:00Z")));
    }

    @Test
    void serializesNotFoundAndKeepsTheExistingExcludedSampleCount() {
        PreferenceSearchResult result = preferenceResult(
                true,
                1,
                new PreferenceImpactAnalysis.Result(
                        2,
                        List.of(new PreferenceImpactAnalysis.FilterImpact(
                                "altitudeDegrees",
                                0,
                                LOOK_AHEAD_DAYS,
                                null))));

        JsonNode response = MAPPER.valueToTree(
                OpportunitySearchResponse.withPreferences(result, List.of(), 0));

        assertEquals(1, response.path("excludedSampleCount").intValue());
        assertEquals(2, response.at("/preferenceImpact/unfilteredOpportunityCount").intValue());
        assertEquals("altitudeDegrees", response.at("/preferenceImpact/filters/0/filter").asString());
        assertEquals(0, response.at("/preferenceImpact/filters/0/matchingOpportunityCount").intValue());
        assertEquals("not_found", response.at("/preferenceImpact/filters/0/status").asString());
        assertEquals(200, response.at("/preferenceImpact/filters/0/lookAheadDays").intValue());
        assertFalse(response.at("/preferenceImpact/filters/0").has("nextMatchAt"));
        assertFalse(response.has("phaseOrientationAvailability"));
    }

    @Test
    void omitsPreferenceImpactWithoutActiveFilters() {
        PreferenceSearchResult result = preferenceResult(
                false,
                0,
                null);

        JsonNode response = MAPPER.valueToTree(
                OpportunitySearchResponse.withPreferences(result, List.of(), 0));

        assertFalse(response.has("emptyReason"));
        assertFalse(response.has("preferenceImpact"));
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
            PreferenceImpactAnalysis.Result preferenceImpact
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
                preferenceImpact == null
                        ? Map.of()
                        : Map.of("altitudeDegrees", Map.of("minimum", 80.0, "maximum", 90.0)),
                excludedSamples,
                removedAll,
                Map.of(),
                preferenceImpact);
    }
}
