package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.OpportunityResponse;
import dev.moonservice.backend.opportunity.OpportunitySearchResponse;
import dev.moonservice.backend.opportunity.OpportunitySearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class OpportunitySearchController {
    private final OpportunitySearchService opportunitySearchService;

    OpportunitySearchController(OpportunitySearchService opportunitySearchService) {
        this.opportunitySearchService = opportunitySearchService;
    }

    @GetMapping("/api/opportunities")
    OpportunityResponse searchByQuery(@RequestParam(name = "q", required = false) String query) {
        return opportunitySearchService.searchByQuery(query);
    }

    @PostMapping("/api/opportunities/search")
    OpportunitySearchResponse search(@RequestBody JsonNode request) {
        return opportunitySearchService.search(request);
    }
}
