package dev.moonservice.backend.opportunity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpportunitySearchServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void delegatesParsedOpportunitySearchRequestToConfiguredEngine() throws Exception {
        OpportunitySearchService opportunitySearchService = new OpportunitySearchService(request -> {
            assertEquals("prague-cz", request.locationId());
            assertEquals("2026-06-29", request.start());
            assertEquals(7, request.forecastHorizonDays());
            assertEquals(12.0, request.maxMoonAltitudeDegrees());
            assertEquals(5, request.limit());
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
                    List.of(),
                    List.of(),
                    List.of());
        });

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
}
