package dev.moonservice.backend.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.opportunity.OpportunitySearchDefaults;
import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine;
import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine.PreferenceSearchResult;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OpportunitySearchControllerUnitTest {
    @Test
    void mapsUnavailableLocationLookupToServiceUnavailable() {
        OpportunitySearchController controller = new OpportunitySearchController(
                new OpportunitySearchService(engineThatMustNotBeCalled(), query ->
                        LocationResolution.temporarilyUnavailable(), new OpportunitySearchDefaults(Clock.systemUTC())));

        ResponseEntity<OpportunityResponse> response = controller.searchByQuery("Praha", null, null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("temporarily_unavailable", response.getBody().status());
    }

    private static OpportunitySearchEngine engineThatMustNotBeCalled() {
        return new OpportunitySearchEngine() {
            @Override
            public OpportunitySearchResponse search(OpportunitySearchRequest request) {
                return fail("Engine should not be called when location lookup is unavailable.");
            }

            @Override
            public OpportunitySearchResponse search(
                    ResolvedLocation location,
                    OpportunitySearchRequest request,
                    Instant notBefore
            ) {
                return fail("Resolved-location engine should not be called when location lookup is unavailable.");
            }

            @Override
            public PreferenceSearchResult search(
                    ResolvedLocation location,
                    OpportunitySearchRequest request,
                    Instant notBefore,
                    OpportunityPreferences preferences
            ) {
                return fail("Preference-aware engine should not be called when location lookup is unavailable.");
            }
        };
    }
}
