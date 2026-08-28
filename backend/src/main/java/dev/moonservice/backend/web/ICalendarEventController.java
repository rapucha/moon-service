package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import jakarta.servlet.http.HttpServletRequest;
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
    private static final int OPPORTUNITY_ID_LIMIT = 200;
    private static final MediaType ICALENDAR_MEDIA_TYPE =
            new MediaType("text", "calendar", StandardCharsets.UTF_8);

    private final OpportunitySearchService opportunitySearchService;
    private final Clock clock;

    ICalendarEventController(OpportunitySearchService opportunitySearchService, Clock clock) {
        this.opportunitySearchService = Objects.requireNonNull(
                opportunitySearchService, "opportunitySearchService");
        this.clock = Objects.requireNonNull(clock, "clock");
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
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(ICALENDAR_MEDIA_TYPE);
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"moon-opportunity.ics\"");
            headers.setCacheControl("no-store");
            headers.setContentLength(calendar.length);
            return head
                    ? ResponseEntity.ok().headers(headers).build()
                    : ResponseEntity.ok().headers(headers).body(calendar);
        } catch (InvalidOpportunitySearchRequestException exception) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", exception.getMessage(), head);
        } catch (RuntimeException exception) {
            return temporarilyUnavailable(head);
        }
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

    private ResponseEntity<?> temporarilyUnavailable(boolean head) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "temporarily_unavailable",
                "Moon opportunities are temporarily unavailable.",
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
        if (opportunityId == null || opportunityId.isBlank()) {
            throw invalid("opportunityId must be non-empty.");
        }
        if (containsUnsupportedControlCharacter(opportunityId)) {
            throw invalid("opportunityId contains unsupported control characters.");
        }
        if (opportunityId.codePointCount(0, opportunityId.length()) > OPPORTUNITY_ID_LIMIT) {
            throw invalid("opportunityId must be 200 characters or fewer.");
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
