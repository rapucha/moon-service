package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest.Order;
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
            @RequestParam(name = "locationId", required = false) String locationId,
            @RequestParam(name = "order", required = false) String rawOrder
    ) {
        Order order = Order.fromProductQuery(rawOrder);
        OpportunityResponse response = opportunitySearchService.search(query, locationId, order);
        response = OpportunityCalendarLinkAssembler.withCalendarLinks(
                response, order, null, null);
        return ResponseEntity.status(httpStatusFor(response)).body(response);
    }

    @PostMapping(value = "/api/opportunities", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OpportunityResponse> searchWithPreferences(
            HttpServletRequest request,
            @RequestParam(name = "order", required = false) String rawOrder
    ) {
        Order order = Order.fromProductQuery(rawOrder);
        ProductRequestParser.OpportunityRequest productRequest =
                ProductRequestParser.parseOpportunity(request);
        OpportunityResponse response = searchProductRequest(productRequest, order);
        response = OpportunityCalendarLinkAssembler.withCalendarLinks(
                response,
                order,
                productRequest.weatherRanking(),
                productRequest.preferences());
        response = withFilteredAtomLink(response, productRequest);
        return ResponseEntity.status(httpStatusFor(response))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    private OpportunityResponse searchProductRequest(
            ProductRequestParser.OpportunityRequest request,
            Order order
    ) {
        ProductWeatherRanking weatherRanking = request.weatherRanking();
        if (request.preferences() == null) {
            return weatherRanking == null
                    ? opportunitySearchService.search(request.query(), request.locationId(), order)
                    : opportunitySearchService.search(
                            request.query(), request.locationId(), order, weatherRanking.scoringValue());
        }
        ProductRequestParser.IgnoredFields ignoredFields = request.ignoredFields();
        return weatherRanking == null
                ? opportunitySearchService.search(
                        request.query(),
                        request.locationId(),
                        order,
                        request.preferences(),
                        ignoredFields.paths(),
                        ignoredFields.count())
                : opportunitySearchService.search(
                        request.query(),
                        request.locationId(),
                        order,
                        request.preferences(),
                        ignoredFields.paths(),
                        ignoredFields.count(),
                        weatherRanking.scoringValue());
    }

    private static OpportunityResponse withFilteredAtomLink(
            OpportunityResponse source,
            ProductRequestParser.OpportunityRequest request
    ) {
        if (!(source instanceof OpportunitySearchResponse response)
                || !"ok".equals(response.status())) {
            return source;
        }
        boolean hasActivePreferences = request.preferences() != null
                && response.normalizedActiveFilters() != null
                && !response.normalizedActiveFilters().isEmpty();
        ProductWeatherRanking weatherRanking = request.weatherRanking();
        boolean hasAppliedNondefaultWeather = weatherRanking != null
                && weatherRanking != ProductWeatherRanking.BALANCED
                && weatherRanking.scoringValue().wireValue().equals(response.appliedWeatherRanking());
        if (!hasActivePreferences && !hasAppliedNondefaultWeather) {
            return source;
        }
        String query = PublicPreferenceQuery.calendarQuery(
                response.location().id(),
                Order.BEST_MATCH,
                hasAppliedNondefaultWeather ? weatherRanking : null,
                hasActivePreferences ? request.preferences() : null);
        return response.withFilteredAtomLink("/feeds/atom" + query);
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
