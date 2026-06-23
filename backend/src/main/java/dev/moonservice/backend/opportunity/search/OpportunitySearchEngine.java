package dev.moonservice.backend.opportunity.search;

import dev.moonservice.backend.location.ResolvedLocation;

public interface OpportunitySearchEngine {
    OpportunitySearchResponse search(OpportunitySearchRequest request);

    default boolean supportsLocation(ResolvedLocation location) {
        return true;
    }
}
