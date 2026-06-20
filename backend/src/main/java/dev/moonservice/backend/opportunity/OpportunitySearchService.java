package dev.moonservice.backend.opportunity;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class OpportunitySearchService {
    private final OpportunitySearchEngine opportunitySearchEngine;

    public OpportunitySearchService(OpportunitySearchEngine opportunitySearchEngine) {
        this.opportunitySearchEngine = opportunitySearchEngine;
    }

    public String search(JsonNode request) {
        return opportunitySearchEngine.search(OpportunitySearchRequest.fromJson(request));
    }
}
