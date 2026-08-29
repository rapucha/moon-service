package dev.moonservice.backend.web;

import dev.moonservice.backend.observability.RequestLoggingFilter;
import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest.Order;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.backend.opportunity.search.OpportunityStatusResponse;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.scoring.WeatherRanking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class OpportunityFilteredAtomLinkFunctionalTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LOCATION_ID = "moon-service-3067696";
    private static final String FILTERED_ATOM_LINK = "/feeds/atom?locationId=" + LOCATION_ID
            + "&weatherRanking=prefer_clear";
    private static final String INVARIANT_EVENT = "filtered_atom_link_invariant_failed";
    private static final String UNTRUSTED_REQUEST_ID = "raw-header-marker with spaces";
    private static final String CANONICAL_PREFERENCES = "%7B%22version%22%3A1%2C"
            + "%22altitudeDegrees%22%3A%7B%22minimum%22%3A5%2C%22maximum%22%3A12%7D%7D";

    @Test
    void returnsCanonicalFilteredAtomLinkAndPreservesCalendarLink() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(
                eq("private-query-marker"),
                isNull(),
                eq(Order.SOONEST),
                any(OpportunityPreferences.class),
                anyList(),
                anyInt(),
                eq(WeatherRanking.PREFER_CLEAR)))
                .thenReturn(response(
                        Map.of("altitudeDegrees", Map.of("minimum", 5.0, "maximum", 12.0)),
                        "prefer_clear"));

        String atomLink = "/feeds/atom?locationId=" + LOCATION_ID
                + "&weatherRanking=prefer_clear&preferences=" + CANONICAL_PREFERENCES;
        String calendarLink = "/o/opportunity-1.ics?locationId=" + LOCATION_ID
                + "&order=soonest&weatherRanking=prefer_clear&preferences=" + CANONICAL_PREFERENCES;
        MvcResult result = mvc(search).perform(post("/api/opportunities")
                        .queryParam("order", "soonest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"q":"private-query-marker","weatherRanking":"prefer_clear",
                                 "preferences":{"version":1,
                                   "altitudeDegrees":{"maximum":12.000,"minimum":5.000}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.atomWithFilters").value(atomLink))
                .andExpect(jsonPath("$.opportunities[0].links.ics").value(calendarLink))
                .andExpect(jsonPath("$.opportunities[0].links.result").value("/search/result"))
                .andReturn();

        assertFalse(result.getResponse().getContentAsString().contains("private-query-marker"));
    }

    @Test
    void returnsFilteredAtomLinkForNondefaultWeatherAlone() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(
                eq("weather-query-marker"),
                isNull(),
                eq(Order.BEST_MATCH),
                eq(WeatherRanking.IGNORE_WEATHER)))
                .thenReturn(response(null, "ignore_weather"));

        mvc(search).perform(post("/api/opportunities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"q":"weather-query-marker","weatherRanking":"ignore_weather"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.atomWithFilters").value(
                        "/feeds/atom?locationId=" + LOCATION_ID + "&weatherRanking=ignore_weather"));
    }

    @Test
    void omitsFilteredAtomLinkForGetAndBalancedEmptyPreferences() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(eq("plain-query"), isNull(), eq(Order.BEST_MATCH)))
                .thenReturn(response(null, null));
        when(search.search(
                eq("balanced-query"),
                isNull(),
                eq(Order.BEST_MATCH),
                any(OpportunityPreferences.class),
                anyList(),
                anyInt(),
                eq(WeatherRanking.BALANCED)))
                .thenReturn(response(Map.of(), "balanced"));
        MockMvc mvc = mvc(search);

        mvc.perform(get("/api/opportunities").queryParam("q", "plain-query"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.atomWithFilters").doesNotExist());
        mvc.perform(post("/api/opportunities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"q":"balanced-query","weatherRanking":"balanced",
                                 "preferences":{"version":1}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.atomWithFilters").doesNotExist());
    }

    @Test
    void rejectsActiveNormalizedFiltersWithoutRootLink(CapturedOutput output) throws Exception {
        OpportunitySearchResponse source = response(
                Map.of("altitudeDegrees", Map.of("minimum", 5.0)), null);

        assertInvariantFailure(source, output);
    }

    @Test
    void rejectsPreferClearWithoutRootLink(CapturedOutput output) throws Exception {
        assertInvariantFailure(response(null, "prefer_clear"), output);
    }

    @Test
    void rejectsIgnoreWeatherWithBlankRootLink(CapturedOutput output) throws Exception {
        OpportunitySearchResponse source = response(null, "ignore_weather")
                .withFilteredAtomLink(" \t");

        assertInvariantFailure(source, output);
    }

    @Test
    void passesValidFilteredResponseThroughWithExactBody(CapturedOutput output) {
        OpportunitySearchResponse source = response(null, "prefer_clear")
                .withFilteredAtomLink(FILTERED_ATOM_LINK);

        ResponseEntity<OpportunityResponse> result =
                OpportunitySearchController.finalProductResponse(source);

        assertEquals(200, result.getStatusCode().value());
        assertEquals("no-store", result.getHeaders().getCacheControl());
        assertSame(source, result.getBody());
        assertFalse(output.getAll().contains(INVARIANT_EVENT));
    }

    @Test
    void passesAllOffBalancedAndNonOkResponsesThroughWithoutLogging(CapturedOutput output) {
        OpportunitySearchResponse allOff = response(null, null);
        OpportunitySearchResponse balanced = response(Map.of(), "balanced");
        OpportunityStatusResponse nonOk = new OpportunityStatusResponse(
                "temporarily_unavailable",
                "2026-08-28T00:00:00Z",
                "provider fixture detail");

        assertPassesThrough(allOff, 200);
        assertPassesThrough(balanced, 200);
        assertPassesThrough(nonOk, 503);
        assertFalse(output.getAll().contains(INVARIANT_EVENT));
    }

    private static void assertInvariantFailure(
            OpportunitySearchResponse source,
            CapturedOutput output
    ) throws Exception {
        Instant startedAt = Instant.now();
        MvcResult result = invariantMvc(source).perform(get("/invariant-test")
                        .header(RequestLoggingFilter.REQUEST_ID_HEADER, UNTRUSTED_REQUEST_ID)
                        .queryParam("q", "private-query-marker")
                        .queryParam("locationId", LOCATION_ID)
                        .queryParam("url", "https://private.example/private-url-marker")
                        .queryParam("preferences", "private-filter-marker")
                        .queryParam("weatherRanking", "private-weather-marker"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("temporarily_unavailable"))
                .andExpect(jsonPath("$.generatedAt").isString())
                .andExpect(jsonPath("$.message").value(
                        "Opportunity lookup is temporarily unavailable."))
                .andReturn();
        Instant completedAt = Instant.now();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsByteArray());
        assertEquals(3, body.size());
        Instant generatedAt = Instant.parse(body.path("generatedAt").asString());
        assertFalse(generatedAt.isBefore(startedAt));
        assertFalse(generatedAt.isAfter(completedAt));

        String responseRequestId = result.getResponse()
                .getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);
        assertNotNull(responseRequestId);
        assertNotEquals(UNTRUSTED_REQUEST_ID, responseRequestId);
        assertTrue(responseRequestId.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));

        List<String> invariantLines = output.getAll().lines()
                .filter(line -> line.contains(INVARIANT_EVENT))
                .toList();
        assertEquals(1, invariantLines.size());
        String invariantLine = invariantLines.getFirst();
        assertTrue(invariantLine.endsWith(
                INVARIANT_EVENT + " requestId=" + responseRequestId));
        for (String privateMarker : List.of(
                UNTRUSTED_REQUEST_ID,
                LOCATION_ID,
                "Prague",
                "private-query-marker",
                "private-url-marker",
                "private-filter-marker",
                "private-weather-marker",
                "altitudeDegrees",
                "prefer_clear",
                "ignore_weather",
                "opportunity-1",
                "/search/result",
                "2099-01-02",
                "Good conditions")) {
            assertFalse(invariantLine.contains(privateMarker),
                    () -> "Invariant event leaked fixture marker: " + privateMarker);
        }
    }

    private static void assertPassesThrough(OpportunityResponse source, int expectedStatus) {
        ResponseEntity<OpportunityResponse> result =
                OpportunitySearchController.finalProductResponse(source);

        assertEquals(expectedStatus, result.getStatusCode().value());
        assertEquals("no-store", result.getHeaders().getCacheControl());
        assertSame(source, result.getBody());
    }

    private static MockMvc invariantMvc(OpportunityResponse source) {
        return MockMvcBuilders.standaloneSetup(new FinalProductResponseController(source))
                .addFilters(new RequestLoggingFilter())
                .build();
    }

    private static MockMvc mvc(OpportunitySearchService search) {
        return MockMvcBuilders.standaloneSetup(new OpportunitySearchController(search)).build();
    }

    private static OpportunitySearchResponse response(
            Map<String, Object> normalizedActiveFilters,
            String appliedWeatherRanking
    ) {
        return new OpportunitySearchResponse(
                "ok",
                "2026-08-28T00:00:00Z",
                new OpportunitySearchResponse.Location(
                        LOCATION_ID, "real_location", "Prague, Czechia",
                        50.08804, 14.42076, 202, "Europe/Prague", "CZ"),
                7,
                "2026-08-28T00:00:00Z",
                "2026-09-04T00:00:00Z",
                1,
                90.0,
                List.of(opportunity()),
                List.of(),
                List.of(),
                appliedWeatherRanking,
                normalizedActiveFilters == null ? null : 1,
                normalizedActiveFilters,
                normalizedActiveFilters == null ? null : 0,
                normalizedActiveFilters == null ? null : List.of(),
                normalizedActiveFilters == null ? null : 0,
                normalizedActiveFilters == null ? null : 0,
                null,
                null,
                "2026-08-28T00:00:00Z",
                null,
                null);
    }

    private static OpportunitySearchResponse.Opportunity opportunity() {
        return new OpportunitySearchResponse.Opportunity(
                "opportunity-1",
                "moonrise_low",
                null,
                "2099-01-02T02:00:00Z",
                "2099-01-02T02:30:00Z",
                "2099-01-02T03:00:00Z",
                "Europe/Prague",
                80,
                "high",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "Good conditions.",
                Map.of("result", "/search/result"));
    }

    @RestController
    private static final class FinalProductResponseController {
        private final OpportunityResponse source;

        private FinalProductResponseController(OpportunityResponse source) {
            this.source = source;
        }

        @GetMapping("/invariant-test")
        ResponseEntity<OpportunityResponse> response() {
            return OpportunitySearchController.finalProductResponse(source);
        }
    }
}
