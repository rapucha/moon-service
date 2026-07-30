package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.planning.MoonPlanningResponse;
import dev.moonservice.backend.opportunity.planning.MoonPlanningService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MoonPlanningController {
    private final MoonPlanningService moonPlanningService;

    MoonPlanningController(MoonPlanningService moonPlanningService) {
        this.moonPlanningService = moonPlanningService;
    }

    @PostMapping(value = "/api/opportunities/planning", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MoonPlanningResponse> search(HttpServletRequest request) {
        ProductRequestParser.PlanningRequest planningRequest =
                ProductRequestParser.parsePlanning(request);
        ProductRequestParser.IgnoredFields ignoredFields = planningRequest.ignoredFields();
        MoonPlanningResponse response = moonPlanningService.search(
                planningRequest.locationId(),
                planningRequest.preferences(),
                ignoredFields.paths(),
                ignoredFields.count());
        HttpStatus status = "temporarily_unavailable".equals(response.status())
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.OK;
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }
}
