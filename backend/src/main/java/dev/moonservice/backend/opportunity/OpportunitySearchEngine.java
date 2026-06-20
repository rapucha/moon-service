package dev.moonservice.backend.opportunity;

public interface OpportunitySearchEngine {
    OpportunitySearchResponse search(OpportunitySearchRequest request);
}
