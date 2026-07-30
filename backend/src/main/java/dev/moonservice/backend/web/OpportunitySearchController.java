package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    ResponseEntity<OpportunityResponse> searchByQuery(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "locationId", required = false) String locationId
    ) {
        OpportunityResponse response = opportunitySearchService.search(query, locationId);
        return ResponseEntity.status(httpStatusFor(response)).body(response);
    }

    @PostMapping(value = "/api/opportunities", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OpportunityResponse> searchWithPreferences(HttpServletRequest request) {
        ProductRequestParser.OpportunityRequest productRequest =
                ProductRequestParser.parseOpportunity(request);
        ProductRequestParser.IgnoredFields ignoredFields = productRequest.ignoredFields();
        OpportunityResponse response = productRequest.preferences() == null
                ? opportunitySearchService.search(productRequest.query(), productRequest.locationId())
                : opportunitySearchService.search(
                        productRequest.query(),
                        productRequest.locationId(),
                        productRequest.preferences(),
                        ignoredFields.paths(),
                        ignoredFields.count());
        return ResponseEntity.status(httpStatusFor(response))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    @PostMapping("/api/opportunities/search")
    OpportunitySearchResponse search(@RequestBody JsonNode request) {
        return opportunitySearchService.search(request);
    }

    private static HttpStatus httpStatusFor(OpportunityResponse response) {
        if ("temporarily_unavailable".equals(response.status())) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.OK;
    }
}
