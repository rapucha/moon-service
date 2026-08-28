package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest.Order;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.scoring.WeatherRanking;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpportunityFilteredAtomLinkFunctionalTest {
    private static final String LOCATION_ID = "moon-service-3067696";
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
                .andExpect(jsonPath("$.links").doesNotExist());
        mvc.perform(post("/api/opportunities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"q":"balanced-query","weatherRanking":"balanced",
                                 "preferences":{"version":1}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links").doesNotExist());
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
                "2026-08-29T02:00:00Z",
                "2026-08-29T02:30:00Z",
                "2026-08-29T03:00:00Z",
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
}
