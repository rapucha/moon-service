package dev.moonservice.backend.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.opportunity.OpportunitySearchDefaults;
import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OpportunitySearchControllerUnitTest {
    @Test
    void mapsUnavailableLocationLookupToServiceUnavailable() {
        OpportunitySearchController controller = new OpportunitySearchController(
                new OpportunitySearchService(request ->
                        fail("Engine should not be called when location lookup is unavailable."), query ->
                        LocationResolution.temporarilyUnavailable(), new OpportunitySearchDefaults(Clock.systemUTC())));

        ResponseEntity<OpportunityResponse> response = controller.searchByQuery("Praha", null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("temporarily_unavailable", response.getBody().status());
    }
}
