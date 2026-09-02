package dev.moonservice.backend.web;

import dev.moonservice.backend.events.MoonEventService;
import dev.moonservice.backend.events.MoonEventResponse;
import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MoonEventController {
    private final MoonEventService moonEventService;

    MoonEventController(MoonEventService moonEventService) {
        this.moonEventService = moonEventService;
    }

    @PostMapping(value = "/api/moon-events", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MoonEventResponse> search(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new InvalidOpportunitySearchRequestException(
                    "Moon event requests must not use URL query parameters.");
        }
        MoonEventRequestParser.MoonEventRequest eventRequest =
                MoonEventRequestParser.parse(request);
        ProductRequestParser.IgnoredFields ignoredFields = eventRequest.ignoredFields();
        MoonEventResponse response = moonEventService.search(
                eventRequest.locationId(),
                eventRequest.preferences(),
                eventRequest.eventHorizonMonths(),
                ignoredFields.paths(),
                ignoredFields.count());
        if (response instanceof MoonEventResponse.Success success) {
            response = withCalendarLinks(
                    success, eventRequest.preferences(), eventRequest.eventHorizonMonths());
        }
        HttpStatus status = "temporarily_unavailable".equals(response.status())
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.OK;
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    private static MoonEventResponse.Success withCalendarLinks(
            MoonEventResponse.Success response,
            OpportunityPreferences preferences,
            int eventHorizonMonths
    ) {
        return new MoonEventResponse.Success(
                response.status(), response.generatedAt(), response.startsAt(), response.endsAt(),
                response.location(), response.appliedPreferenceVersion(),
                response.normalizedActiveFilters(), response.ignoredPreferenceFields(),
                response.ignoredPreferenceFieldCount(),
                response.additionalIgnoredPreferenceFieldCount(),
                response.events().stream().map(event -> {
                    String link = PublicPreferenceQuery.moonEventCalendarLink(
                            event.id(), response.location().id(), preferences, eventHorizonMonths);
                    return event.withLinks(new MoonEventResponse.Links(link));
                }).toList());
    }
}
