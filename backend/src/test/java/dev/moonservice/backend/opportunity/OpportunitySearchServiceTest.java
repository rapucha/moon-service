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
import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.backend.opportunity.search.OpportunityStatusResponse;
import dev.moonservice.backend.weather.WeatherForecastUnavailableException;
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
        ResolvedLocation prague = new ResolvedLocation(
                "prague-cz",
                providerLocationId("prague-cz"),
                "Prague, Czechia",
                50.08804,
                14.42076,
                202,
                ZoneId.of("Europe/Prague"),
                "CZ");
        OpportunitySearchService opportunitySearchService = new OpportunitySearchService(new OpportunitySearchEngine() {
            @Override
            public OpportunitySearchResponse search(OpportunitySearchRequest request) {
                fail("Query search should pass the resolved location to the engine.");
                return okResponse();
            }

            @Override
            public OpportunitySearchResponse search(ResolvedLocation location, OpportunitySearchRequest request) {
                assertEquals(prague, location);
                assertEquals("prague-cz", request.locationId());
                assertEquals("2026-06-21", request.start());
                assertEquals(7, request.forecastHorizonDays());
                assertEquals(90.0, request.maxMoonAltitudeDegrees());
                assertEquals(5, request.limit());
                return okResponse();
            }
        }, query -> {
            assertEquals(new LocationQuery("Praha"), query);
            return LocationResolution.resolved(prague);
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
                providerLocationId("test-location"),
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
                                providerLocationId("springfield-mo-us"),
                                "Springfield, Missouri, United States",
                                37.21533,
                                -93.29824,
                                396,
                                ZoneId.of("America/Chicago"),
                                "US"),
                        new ResolvedLocation(
                                "springfield-il-us",
                                providerLocationId("springfield-il-us"),
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

    @Test
    void returnsTemporarilyUnavailableWhenWeatherProviderFails() {
        ResolvedLocation amsterdam = new ResolvedLocation(
                "amsterdam-nl",
                new ProviderLocationId(LocationProvider.OPEN_METEO, "2759794"),
                "Amsterdam, North Holland, Netherlands",
                52.37403,
                4.88969,
                13,
                ZoneId.of("Europe/Amsterdam"),
                "NL");
        OpportunitySearchService opportunitySearchService = new OpportunitySearchService(new OpportunitySearchEngine() {
            @Override
            public OpportunitySearchResponse search(OpportunitySearchRequest request) {
                fail("Query search should pass the resolved location to the engine.");
                return okResponse();
            }

            @Override
            public OpportunitySearchResponse search(ResolvedLocation location, OpportunitySearchRequest request) {
                throw new WeatherForecastUnavailableException("Weather provider failed.");
            }
        }, query -> LocationResolution.resolved(amsterdam), defaults);

        OpportunityResponse response = opportunitySearchService.searchByQuery("Amsterdam");

        assertEquals("temporarily_unavailable", response.status());
        assertEquals(
                "Opportunity weather lookup is temporarily unavailable.",
                ((OpportunityStatusResponse) response).message());
    }

    @Test
    void scoresResolvedNonFixtureLocationThroughEngine() {
        ResolvedLocation amsterdam = new ResolvedLocation(
                "amsterdam-nl",
                new ProviderLocationId(LocationProvider.OPEN_METEO, "2759794"),
                "Amsterdam, North Holland, Netherlands",
                52.37403,
                4.88969,
                13,
                ZoneId.of("Europe/Amsterdam"),
                "NL");
        OpportunitySearchService opportunitySearchService = new OpportunitySearchService(new OpportunitySearchEngine() {
            @Override
            public OpportunitySearchResponse search(OpportunitySearchRequest request) {
                fail("Query search should pass the resolved location to the engine.");
                return okResponse();
            }

            @Override
            public OpportunitySearchResponse search(ResolvedLocation location, OpportunitySearchRequest request) {
                assertEquals(amsterdam, location);
                assertEquals("amsterdam-nl", request.locationId());
                return okResponse();
            }
        }, query -> LocationResolution.resolved(amsterdam), defaults);

        OpportunityResponse response = opportunitySearchService.searchByQuery("Amsterdam");

        assertEquals("ok", response.status());
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

    private static ProviderLocationId providerLocationId(String providerId) {
        return new ProviderLocationId(LocationProvider.OPEN_METEO, providerId);
    }
}
