package dev.moonservice.backend.web;

import dev.moonservice.backend.events.MoonEventResponse;
import dev.moonservice.backend.events.MoonEventService;
import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@RestController
final class ICalendarEventController {
    private static final int ID_LIMIT = 200;
    private static final MediaType ICALENDAR_MEDIA_TYPE =
            new MediaType("text", "calendar", StandardCharsets.UTF_8);

    private final OpportunitySearchService opportunitySearchService;
    private final Clock clock;
    private MoonEventService moonEventService;

    ICalendarEventController(OpportunitySearchService opportunitySearchService, Clock clock) {
        this.opportunitySearchService = Objects.requireNonNull(
                opportunitySearchService, "opportunitySearchService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Autowired
    void setMoonEventService(MoonEventService moonEventService) {
        this.moonEventService = Objects.requireNonNull(moonEventService, "moonEventService");
    }

    @GetMapping(
            value = {"/o/{opportunityId}.ics", "/o/.ics"},
            produces = "text/calendar;charset=UTF-8"
    )
    ResponseEntity<?> event(
            @PathVariable(name = "opportunityId", required = false) String opportunityId,
            HttpServletRequest request
    ) {
        boolean head = "HEAD".equals(request.getMethod());
        try {
            validateOpportunityId(opportunityId);
            PublicPreferenceQuery.CalendarRequest calendarRequest =
                    PublicPreferenceQuery.parseCalendar(request);
            OpportunityResponse response = search(calendarRequest);
            if (!(response instanceof OpportunitySearchResponse success)
                    || !"ok".equals(success.status())) {
                return statusError(response, head);
            }
            OpportunitySearchResponse.Opportunity opportunity = success.opportunities().stream()
                    .filter(candidate -> opportunityId.equals(candidate.id()))
                    .findFirst()
                    .orElse(null);
            if (opportunity == null) {
                return error(
                        HttpStatus.NOT_FOUND,
                        "opportunity_not_found",
                        "This opportunity is no longer available. Refresh the Moon Service search.",
                        head);
            }
            byte[] calendar = ICalendarEventRenderer.render(
                    success.location(), opportunity, Instant.parse(success.generatedAt()));
            return calendar(calendar, "moon-opportunity.ics", head);
        } catch (InvalidOpportunitySearchRequestException exception) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", exception.getMessage(), head);
        } catch (RuntimeException exception) {
            return temporarilyUnavailable(head);
        }
    }

    @GetMapping(
            value = {"/events/{eventId}.ics", "/events/.ics"},
            produces = "text/calendar;charset=UTF-8"
    )
    ResponseEntity<?> moonEvent(
            @PathVariable(name = "eventId", required = false) String eventId,
            HttpServletRequest request
    ) {
        boolean head = "HEAD".equals(request.getMethod());
        try {
            validateId(eventId, "eventId");
            PublicPreferenceQuery.MoonEventCalendarRequest calendarRequest =
                    PublicPreferenceQuery.parseMoonEventCalendar(request);
            ProductRequestParser.IgnoredFields ignored = calendarRequest.ignoredFields();
            MoonEventResponse response = moonEventService.search(
                    calendarRequest.locationId(),
                    calendarRequest.preferences(),
                    calendarRequest.eventHorizonMonths(),
                    ignored.paths(),
                    ignored.count());
            if (!(response instanceof MoonEventResponse.Success success)
                    || !"ok".equals(success.status())) {
                return moonEventStatusError(response, head);
            }
            MoonEventResponse.MoonEvent selected = success.events().stream()
                    .filter(candidate -> eventId.equals(candidate.id()))
                    .findFirst()
                    .orElse(null);
            if (selected == null) {
                return error(
                        HttpStatus.NOT_FOUND,
                        "event_not_found",
                        "This Moon event is no longer available. Refresh the Moon Service search.",
                        head);
            }
            byte[] content = ICalendarEventRenderer.renderMoonEvent(
                    success.location(), selected, Instant.parse(success.generatedAt()));
            return calendar(content, "moon-event.ics", head);
        } catch (InvalidOpportunitySearchRequestException exception) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", exception.getMessage(), head);
        } catch (RuntimeException exception) {
            return moonEventsTemporarilyUnavailable(head);
        }
    }

    private ResponseEntity<?> calendar(byte[] content, String filename, boolean head) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(ICALENDAR_MEDIA_TYPE);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"");
        headers.setCacheControl("no-store");
        headers.setContentLength(content.length);
        return head
                ? ResponseEntity.ok().headers(headers).build()
                : ResponseEntity.ok().headers(headers).body(content);
    }

    private OpportunityResponse search(PublicPreferenceQuery.CalendarRequest request) {
        if (request.preferences() == null) {
            return request.weatherRanking() == null
                    ? opportunitySearchService.search(null, request.locationId(), request.order())
                    : opportunitySearchService.search(
                            null,
                            request.locationId(),
                            request.order(),
                            request.weatherRanking().scoringValue());
        }
        ProductRequestParser.IgnoredFields ignoredFields = request.ignoredFields();
        return request.weatherRanking() == null
                ? opportunitySearchService.search(
                        null,
                        request.locationId(),
                        request.order(),
                        request.preferences(),
                        ignoredFields.paths(),
                        ignoredFields.count())
                : opportunitySearchService.search(
                        null,
                        request.locationId(),
                        request.order(),
                        request.preferences(),
                        ignoredFields.paths(),
                        ignoredFields.count(),
                        request.weatherRanking().scoringValue());
    }

    private ResponseEntity<?> statusError(OpportunityResponse response, boolean head) {
        if (response != null && "location_not_found".equals(response.status())) {
            return error(
                    HttpStatus.NOT_FOUND,
                    "location_not_found",
                    "No matching location found.",
                    head);
        }
        return temporarilyUnavailable(head);
    }

    private ResponseEntity<?> moonEventStatusError(MoonEventResponse response, boolean head) {
        if (response != null && "location_not_found".equals(response.status())) {
            return error(
                    HttpStatus.NOT_FOUND,
                    "location_not_found",
                    "No matching location found.",
                    head);
        }
        return moonEventsTemporarilyUnavailable(head);
    }

    private ResponseEntity<?> temporarilyUnavailable(boolean head) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "temporarily_unavailable",
                "Moon opportunities are temporarily unavailable.",
                head);
    }

    private ResponseEntity<?> moonEventsTemporarilyUnavailable(boolean head) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "temporarily_unavailable",
                "Moon events are temporarily unavailable.",
                head);
    }

    private ResponseEntity<?> error(
            HttpStatus httpStatus,
            String status,
            String message,
            boolean head
    ) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(httpStatus)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        return head
                ? response.build()
                : response.body(new ErrorResponse(status, Instant.now(clock).toString(), message));
    }

    private static void validateOpportunityId(String opportunityId) {
        validateId(opportunityId, "opportunityId");
    }

    private static void validateId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must be non-empty.");
        }
        if (containsUnsupportedControlCharacter(value)) {
            throw invalid(field + " contains unsupported control characters.");
        }
        if (value.codePointCount(0, value.length()) > ID_LIMIT) {
            throw invalid(field + " must be 200 characters or fewer.");
        }
    }

    private static boolean containsUnsupportedControlCharacter(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint)
                        || codePoint == 0x061C
                        || codePoint == 0x200E
                        || codePoint == 0x200F
                        || codePoint >= 0x202A && codePoint <= 0x202E
                        || codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    private static InvalidOpportunitySearchRequestException invalid(String message) {
        return new InvalidOpportunitySearchRequestException(message);
    }
}
