package dev.moonservice.backend.opportunity.search;

import dev.moonservice.backend.location.ResolvedLocation;

public interface OpportunitySearchEngine {
    OpportunitySearchResponse search(OpportunitySearchRequest request);

    default OpportunitySearchResponse search(ResolvedLocation location, OpportunitySearchRequest request) {
        return search(request);
    }
}
