package dev.moonservice.backend.opportunity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationProvider;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.ProviderLocationId;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.opportunity.search.LocationCandidatesResponse;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpportunitySearchServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpportunitySearchDefaults defaults = new OpportunitySearchDefaults(
            Clock.fixed(Instant.parse("2026-06-20T22:30:00Z"), ZoneId.of("UTC")));

    @Test
    void delegatesParsedOpportunitySearchRequestToConfiguredEngine() throws Exception {
        OpportunitySearchService opportunitySearchService = new OpportunitySearchService(request -> {
            assertEquals("prague-cz", request.locationId());
            assertEquals("2026-06-29", request.start());
            assertEquals(7, request.forecastHorizonDays());
            assertEquals(12.0, request.maxMoonAltitudeDegrees());
            assertEquals(5, request.limit());
            return okResponse();
        }, query -> {
            throw new AssertionError("Resolver should not be called for direct request search.");
        }, defaults);

        OpportunitySearchResponse response = opportunitySearchService.search(objectMapper.readTree("""
                {
                  "locationId": "prague-cz",
                  "start": "2026-06-29",
                  "forecastHorizonDays": 7,
                  "maxMoonAltitudeDegrees": 12,
                  "limit": 5
                }
                """));

        assertEquals("ok", response.status());
        assertEquals("openmeteo:prague-cz", response.location().id());
    }

    @Test
    void resolvesQueryBeforeDelegatingToConfiguredEngine() {
        OpportunitySearchService opportunitySearchService = new OpportunitySearchService(request -> {
            assertEquals("prague-cz", request.locationId());
            assertEquals("2026-06-21", request.start());
            assertEquals(7, request.forecastHorizonDays());
            assertEquals(90.0, request.maxMoonAltitudeDegrees());
            assertEquals(5, request.limit());
            return okResponse();
        }, query -> {
            assertEquals(new LocationQuery("Praha"), query);
            return LocationResolution.resolved(new ResolvedLocation(
                    "prague-cz",
                    fixtureProviderLocationId("prague-cz"),
                    "Prague, Czechia",
                    50.08804,
                    14.42076,
                    202,
                    ZoneId.of("Europe/Prague"),
                    "CZ"));
        }, defaults);

        OpportunityResponse response = opportunitySearchService.searchByQuery(" Praha ");

        assertEquals("ok", response.status());
    }

    @Test
    void usesResolvedLocationTimezoneForDefaultStartDate() {
        OpportunitySearchService opportunitySearchService = new OpportunitySearchService(request -> {
            assertEquals("2026-06-20", request.start());
            return okResponse();
        }, query -> LocationResolution.resolved(new ResolvedLocation(
                "test-location",
                fixtureProviderLocationId("test-location"),
                "Test Location",
                40.7128,
                -74.0060,
                10,
                ZoneId.of("America/New_York"),
                "US")), defaults);

        OpportunityResponse response = opportunitySearchService.searchByQuery("test");

        assertEquals("ok", response.status());
    }

    @Test
    void returnsAmbiguousLocationWithoutCallingOpportunityEngine() {
        OpportunitySearchService opportunitySearchService = new OpportunitySearchService(request ->
                fail("Engine should not be called until an ambiguous location is selected."), query ->
                LocationResolution.ambiguous(java.util.List.of(
                        new ResolvedLocation(
                                "springfield-mo-us",
                                fixtureProviderLocationId("springfield-mo-us"),
                                "Springfield, Missouri, United States",
                                37.21533,
                                -93.29824,
                                396,
                                ZoneId.of("America/Chicago"),
                                "US"),
                        new ResolvedLocation(
                                "springfield-il-us",
                                fixtureProviderLocationId("springfield-il-us"),
                                "Springfield, Illinois, United States",
                                39.80172,
                                -89.64371,
                                182,
                                ZoneId.of("America/Chicago"),
                                "US"))), defaults);

        OpportunityResponse response = opportunitySearchService.searchByQuery("Springfield");

        assertEquals("ambiguous_location", response.status());
        LocationCandidatesResponse candidatesResponse = (LocationCandidatesResponse) response;
        assertEquals(2, candidatesResponse.candidates().size());
        assertEquals("springfield-mo-us", candidatesResponse.candidates().getFirst().id());
    }

    @Test
    void returnsTemporarilyUnavailableWhenLocationProviderFails() {
        OpportunitySearchService opportunitySearchService = new OpportunitySearchService(request ->
                fail("Engine should not be called when location lookup is unavailable."), query ->
                LocationResolution.temporarilyUnavailable(), defaults);

        OpportunityResponse response = opportunitySearchService.searchByQuery("Praha");

        assertEquals("temporarily_unavailable", response.status());
    }

    private static OpportunitySearchResponse okResponse() {
        return new OpportunitySearchResponse(
                "ok",
                "2026-06-20T17:00:00Z",
                new OpportunitySearchResponse.Location(
                        "openmeteo:prague-cz",
                        "real_location",
                        "Prague, Czechia",
                        50.0755,
                        14.4378,
                        200,
                        "Europe/Prague",
                        "CZ"),
                7,
                "2026-06-29T00:00:00Z",
                "2026-07-06T00:00:00Z",
                3,
                12.0,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of());
    }

    private static ProviderLocationId fixtureProviderLocationId(String providerId) {
        return new ProviderLocationId(LocationProvider.FIXTURE, providerId);
    }
}
