package dev.moonservice.backend.web;

import dev.moonservice.backend.events.LunarEclipseEventService;
import dev.moonservice.backend.events.MoonEventResponse;
import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MoonEventController {
    private final LunarEclipseEventService lunarEclipseEventService;

    MoonEventController(LunarEclipseEventService lunarEclipseEventService) {
        this.lunarEclipseEventService = lunarEclipseEventService;
    }

    @PostMapping(value = "/api/moon-events", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MoonEventResponse> search(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new InvalidOpportunitySearchRequestException(
                    "Moon event requests must not use URL query parameters.");
        }
        ProductRequestParser.PlanningRequest eventRequest =
                ProductRequestParser.parsePlanning(request);
        ProductRequestParser.IgnoredFields ignoredFields = eventRequest.ignoredFields();
        MoonEventResponse response = lunarEclipseEventService.search(
                eventRequest.locationId(),
                eventRequest.preferences(),
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
