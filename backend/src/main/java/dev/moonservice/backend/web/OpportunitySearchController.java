package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class OpportunitySearchController {
    private static final String DATA_ATTRIBUTION =
            "Weather data: Open-Meteo, CC BY 4.0; location data: Open-Meteo Geocoding, "
                    + "based on GeoNames, CC BY-NC 4.0; adapted and aggregated by Moon Service.";
    private static final String[] DATA_ATTRIBUTION_LINKS = {
            "<https://open-meteo.com/>; rel=\"describedby\"; title=\"Open-Meteo\"",
            "<https://www.geonames.org/>; rel=\"describedby\"; title=\"GeoNames\"",
            "<https://creativecommons.org/licenses/by/4.0/>; rel=\"license\"; title=\"CC BY 4.0 weather data\"",
            "<https://creativecommons.org/licenses/by-nc/4.0/>; rel=\"license\"; title=\"CC BY-NC 4.0 location data\""
    };

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
        return ResponseEntity.status(httpStatusFor(response))
                .header("Moon-Data-Attribution", DATA_ATTRIBUTION)
                .header(HttpHeaders.LINK, DATA_ATTRIBUTION_LINKS)
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
