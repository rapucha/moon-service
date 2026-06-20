package dev.moonservice.backend.opportunity;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            return "{\"status\":\"ok\"}";
        });

        String response = opportunitySearchService.search(objectMapper.readTree("""
                {
                  "locationId": "prague-cz",
                  "start": "2026-06-29",
                  "forecastHorizonDays": 7,
                  "maxMoonAltitudeDegrees": 12,
                  "limit": 5
                }
                """));

        assertEquals("{\"status\":\"ok\"}", response);
    }
}
