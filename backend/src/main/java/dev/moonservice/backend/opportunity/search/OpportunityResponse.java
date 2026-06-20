package dev.moonservice.backend.opportunity.search;

public sealed interface OpportunityResponse permits OpportunitySearchResponse, OpportunityStatusResponse {
    String status();
}
