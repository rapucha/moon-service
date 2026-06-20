package dev.moonservice.backend.opportunity;

public sealed interface OpportunityResponse permits OpportunitySearchResponse, OpportunityStatusResponse {
    String status();
}
