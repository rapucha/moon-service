package dev.moonservice.backend.opportunity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpportunitySearchRequestTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsOpportunitySearchRequestFromJson() throws Exception {
        OpportunitySearchRequest request = OpportunitySearchRequest.fromJson(objectMapper.readTree("""
                {
                  "locationId": "prague-cz",
                  "start": "2026-06-29",
                  "forecastHorizonDays": 7,
                  "maxMoonAltitudeDegrees": 12,
                  "limit": 5
                }
                """));

        assertEquals("prague-cz", request.locationId());
        assertEquals("2026-06-29", request.start());
        assertEquals(7, request.forecastHorizonDays());
        assertEquals(12.0, request.maxMoonAltitudeDegrees());
        assertEquals(5, request.limit());
    }

    @Test
    void convertsInstantStartToUtcDate() throws Exception {
        OpportunitySearchRequest request = OpportunitySearchRequest.fromJson(objectMapper.readTree("""
                {
                  "locationId": "prague-cz",
                  "start": "2026-06-29T23:30:00Z",
                  "forecastHorizonDays": 7,
                  "maxMoonAltitudeDegrees": 12,
                  "limit": 5
                }
                """));

        assertEquals("2026-06-29", request.start());
    }

    @Test
    void rejectsUnsupportedStartShapeWithoutTrialParsing() throws Exception {
        InvalidOpportunitySearchRequestException exception = assertThrows(
                InvalidOpportunitySearchRequestException.class,
                () -> OpportunitySearchRequest.fromJson(objectMapper.readTree("""
                        {
                          "locationId": "prague-cz",
                          "start": "not-a-date",
                          "forecastHorizonDays": 7,
                          "maxMoonAltitudeDegrees": 12,
                          "limit": 5
                        }
                        """)));

        assertEquals("Invalid --start value: not-a-date", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void preservesMalformedIsoDateCause() throws Exception {
        InvalidOpportunitySearchRequestException exception = assertThrows(
                InvalidOpportunitySearchRequestException.class,
                () -> OpportunitySearchRequest.fromJson(objectMapper.readTree("""
                        {
                          "locationId": "prague-cz",
                          "start": "2026-13-40",
                          "forecastHorizonDays": 7,
                          "maxMoonAltitudeDegrees": 12,
                          "limit": 5
                        }
                        """)));

        assertEquals("Invalid --start value: 2026-13-40", exception.getMessage());
        assertNotNull(exception.getCause());
    }

    @Test
    void preservesMalformedUtcInstantCause() throws Exception {
        InvalidOpportunitySearchRequestException exception = assertThrows(
                InvalidOpportunitySearchRequestException.class,
                () -> OpportunitySearchRequest.fromJson(objectMapper.readTree("""
                        {
                          "locationId": "prague-cz",
                          "start": "2026-06-29T99:30:00Z",
                          "forecastHorizonDays": 7,
                          "maxMoonAltitudeDegrees": 12,
                          "limit": 5
                        }
                        """)));

        assertEquals("Invalid --start value: 2026-06-29T99:30:00Z", exception.getMessage());
        assertNotNull(exception.getCause());
    }

    @Test
    void rejectsNonObjectRequest() throws Exception {
        InvalidOpportunitySearchRequestException exception = assertThrows(
                InvalidOpportunitySearchRequestException.class,
                () -> OpportunitySearchRequest.fromJson(objectMapper.readTree("[]")));

        assertEquals("Opportunity search request must be a JSON object.", exception.getMessage());
    }
}
