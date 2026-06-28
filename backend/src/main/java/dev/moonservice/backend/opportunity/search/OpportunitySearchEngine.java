package dev.moonservice.backend.opportunity.search;

import dev.moonservice.backend.location.ResolvedLocation;

import java.time.Instant;

public interface OpportunitySearchEngine {
    OpportunitySearchResponse search(OpportunitySearchRequest request);

    default OpportunitySearchResponse search(ResolvedLocation location, OpportunitySearchRequest request) {
        return search(request);
    }

    default OpportunitySearchResponse search(
            ResolvedLocation location,
            OpportunitySearchRequest request,
            Instant notBefore
    ) {
        return search(location, request);
    }
}
