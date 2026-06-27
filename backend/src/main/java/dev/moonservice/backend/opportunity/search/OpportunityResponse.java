package dev.moonservice.backend.opportunity.search;

public sealed interface OpportunityResponse
        permits LocationCandidatesResponse, OpportunitySearchResponse, OpportunityStatusResponse {
    String status();
}
