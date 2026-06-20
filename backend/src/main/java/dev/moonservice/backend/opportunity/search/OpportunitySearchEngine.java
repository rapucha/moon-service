package dev.moonservice.backend.opportunity.search;

public interface OpportunitySearchEngine {
    OpportunitySearchResponse search(OpportunitySearchRequest request);
}
