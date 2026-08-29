package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest.Order;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.backend.opportunity.search.OpportunityStatusResponse;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.scoring.WeatherRanking;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class OpportunityCalendarFeedLinkFunctionalTest {
    private static final String LOCATION_ID = "moon-service-3067696";
    private static final String CALENDAR_FEED =
            "/calendars/opportunities.ics?locationId=" + LOCATION_ID;
    private static final String CANONICAL_PREFERENCES = "%7B%22version%22%3A1%2C"
            + "%22altitudeDegrees%22%3A%7B%22minimum%22%3A5%2C%22maximum%22%3A12%7D%7D";

    @Test
    void getReturnsAllOffCalendarFeedAndPreservesOpportunityLinks() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(eq("private-query-marker"), isNull(), eq(Order.BEST_MATCH)))
                .thenReturn(response("real_location", List.of(opportunity()), null, null));

        MvcResult result = mvc(search).perform(get("/api/opportunities")
                        .queryParam("q", "private-query-marker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.calendarFeed").value(CALENDAR_FEED))
                .andExpect(jsonPath("$.links.atomWithFilters").doesNotExist())
                .andExpect(jsonPath("$.opportunities[0].links.ics").value(
                        "/o/opportunity-1.ics?locationId=" + LOCATION_ID))
                .andExpect(jsonPath("$.opportunities[0].links.result").value("/search/result"))
                .andReturn();

        assertFalse(result.getResponse().getContentAsString().contains("private-query-marker"));
    }

    @Test
    void calendarAssemblerPreservesExistingFilteredAtomLink() {
        String atomLink = "/feeds/atom?locationId=" + LOCATION_ID + "&weatherRanking=prefer_clear";
        OpportunitySearchResponse source = response(
                "real_location", List.of(opportunity()), null, null)
                .withFilteredAtomLink(atomLink);

        OpportunitySearchResponse result = (OpportunitySearchResponse)
                OpportunityCalendarLinkAssembler.withCalendarLinks(
                        source, Order.BEST_MATCH, null, null);

        assertEquals(atomLink, result.links().atomWithFilters());
        assertEquals(CALENDAR_FEED, result.links().calendarFeed());
    }

    @Test
    void postReturnsCanonicalFilteredCalendarFeedWithoutResultOrder() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(
                eq("filtered-query-marker"),
                isNull(),
                eq(Order.SOONEST),
                any(OpportunityPreferences.class),
                anyList(),
                anyInt(),
                eq(WeatherRanking.PREFER_CLEAR)))
                .thenReturn(response(
                        "real_location",
                        List.of(opportunity()),
                        Map.of("altitudeDegrees", Map.of("minimum", 5.0, "maximum", 12.0)),
                        "prefer_clear"));

        String canonicalQuery = "?locationId=" + LOCATION_ID
                + "&weatherRanking=prefer_clear&preferences=" + CANONICAL_PREFERENCES;
        MvcResult result = mvc(search).perform(post("/api/opportunities")
                        .queryParam("order", "soonest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"q":"filtered-query-marker","weatherRanking":"prefer_clear",
                                 "preferences":{"version":1,
                                   "altitudeDegrees":{"maximum":12.000,"minimum":5.000}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.calendarFeed").value(
                        "/calendars/opportunities.ics" + canonicalQuery))
                .andExpect(jsonPath("$.links.atomWithFilters").value(
                        "/feeds/atom" + canonicalQuery))
                .andExpect(jsonPath("$.opportunities[0].links.ics").value(
                        "/o/opportunity-1.ics?locationId=" + LOCATION_ID
                                + "&order=soonest&weatherRanking=prefer_clear&preferences="
                                + CANONICAL_PREFERENCES))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("filtered-query-marker"));
        assertFalse(body.contains("/calendars/opportunities.ics?locationId="
                + LOCATION_ID + "&order="));
    }

    @Test
    void calendarFeedUsesAppliedFiltersAndOmitsIgnoredEmptyPreferences() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(
                eq("weather-query"), isNull(), eq(Order.BEST_MATCH),
                eq(WeatherRanking.IGNORE_WEATHER)))
                .thenReturn(response("real_location", List.of(opportunity()), null, "ignore_weather"));
        when(search.search(
                eq("preference-query"), isNull(), eq(Order.BEST_MATCH),
                any(OpportunityPreferences.class), anyList(), anyInt()))
                .thenReturn(response(
                        "real_location",
                        List.of(opportunity()),
                        Map.of("altitudeDegrees", Map.of("minimum", 5.0, "maximum", 12.0)),
                        null));
        when(search.search(
                eq("ignored-query"), isNull(), eq(Order.BEST_MATCH),
                any(OpportunityPreferences.class), anyList(), anyInt()))
                .thenReturn(response("real_location", List.of(opportunity()), Map.of(), null));
        MockMvc mvc = mvc(search);

        mvc.perform(post("/api/opportunities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"q":"weather-query","weatherRanking":"ignore_weather"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.calendarFeed").value(
                        CALENDAR_FEED + "&weatherRanking=ignore_weather"));
        mvc.perform(post("/api/opportunities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"q":"preference-query","preferences":{"version":1,
                                  "altitudeDegrees":{"maximum":12.000,"minimum":5.000}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.calendarFeed").value(
                        CALENDAR_FEED + "&preferences=" + CANONICAL_PREFERENCES));
        mvc.perform(post("/api/opportunities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"q":"ignored-query","preferences":{"version":1,"future":true}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.calendarFeed").value(CALENDAR_FEED));
    }

    @Test
    void emptyProductResponseStillHasCalendarFeedAndOtherResponsesDoNot() throws Exception {
        OpportunitySearchService search = mock(OpportunitySearchService.class);
        when(search.search(eq("empty-query"), isNull(), eq(Order.BEST_MATCH)))
                .thenReturn(response("real_location", List.of(), null, null));
        when(search.search(eq("non-real-query"), isNull(), eq(Order.BEST_MATCH)))
                .thenReturn(response("fixture_location", List.of(opportunity()), null, null));
        when(search.search(eq("unavailable-query"), isNull(), eq(Order.BEST_MATCH)))
                .thenReturn(new OpportunityStatusResponse(
                        "temporarily_unavailable",
                        "2026-08-28T00:00:00Z",
                        "Provider unavailable."));
        when(search.search(any(JsonNode.class)))
                .thenReturn(response("real_location", List.of(opportunity()), null, null));
        MockMvc mvc = mvc(search);

        mvc.perform(post("/api/opportunities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"empty-query\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opportunities").isEmpty())
                .andExpect(jsonPath("$.links.calendarFeed").value(CALENDAR_FEED));
        mvc.perform(get("/api/opportunities").queryParam("q", "non-real-query"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.calendarFeed").doesNotExist())
                .andExpect(jsonPath("$.opportunities[0].links.ics").value(
                        "/o/opportunity-1.ics?locationId=" + LOCATION_ID));
        mvc.perform(get("/api/opportunities").queryParam("q", "unavailable-query"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.links.calendarFeed").doesNotExist());
        mvc.perform(post("/api/opportunities/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.calendarFeed").doesNotExist());
    }

    private static MockMvc mvc(OpportunitySearchService search) {
        return MockMvcBuilders.standaloneSetup(new OpportunitySearchController(search)).build();
    }

    private static OpportunitySearchResponse response(
            String locationKind,
            List<OpportunitySearchResponse.Opportunity> opportunities,
            Map<String, Object> normalizedActiveFilters,
            String appliedWeatherRanking
    ) {
        return new OpportunitySearchResponse(
                "ok",
                "2026-08-28T00:00:00Z",
                new OpportunitySearchResponse.Location(
                        LOCATION_ID, locationKind, "Prague, Czechia",
                        50.08804, 14.42076, 202, "Europe/Prague", "CZ"),
                7,
                "2026-08-28T00:00:00Z",
                "2026-09-04T00:00:00Z",
                opportunities.size(),
                90.0,
                opportunities,
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
}
