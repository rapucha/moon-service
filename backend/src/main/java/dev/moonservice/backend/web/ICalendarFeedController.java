package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
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
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@RestController
final class ICalendarFeedController {
    private static final MediaType ICALENDAR_MEDIA_TYPE =
            new MediaType("text", "calendar", StandardCharsets.UTF_8);
    private static final String PRIVATE_CACHE_CONTROL = "private, max-age=900";

    private final OpportunitySearchService opportunitySearchService;
    private final Clock clock;

    ICalendarFeedController(OpportunitySearchService opportunitySearchService, Clock clock) {
        this.opportunitySearchService = Objects.requireNonNull(
                opportunitySearchService, "opportunitySearchService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping(
            value = "/calendars/opportunities.ics",
            produces = "text/calendar;charset=UTF-8"
    )
    ResponseEntity<?> feed(HttpServletRequest request) {
        boolean head = "HEAD".equals(request.getMethod());
        try {
            if (request.getParameterMap().containsKey("order")) {
                throw new InvalidOpportunitySearchRequestException(
                        "order is not supported for calendar feeds.");
            }
            PublicPreferenceQuery.CalendarRequest feedRequest =
                    PublicPreferenceQuery.parseCalendar(request);
            OpportunityResponse response = search(feedRequest);
            if (!(response instanceof OpportunitySearchResponse success)
                    || !"ok".equals(success.status())) {
                return statusError(response, head);
            }
            HttpHeaders headers = successHeaders();
            if (head) {
                return ResponseEntity.ok().headers(headers).build();
            }
            byte[] calendar = ICalendarEventRenderer.renderFeed(
                    success.location(),
                    success.opportunities(),
                    Instant.parse(success.generatedAt()));
            headers.setContentLength(calendar.length);
            return ResponseEntity.ok().headers(headers).body(calendar);
        } catch (InvalidOpportunitySearchRequestException exception) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", exception.getMessage(), head);
        } catch (RuntimeException exception) {
            return temporarilyUnavailable(head);
        }
    }

    private OpportunityResponse search(PublicPreferenceQuery.CalendarRequest request) {
        if (request.preferences() == null) {
            return request.weatherRanking() == null
                    ? opportunitySearchService.search(null, request.locationId(), Order.SOONEST)
                    : opportunitySearchService.search(
                            null,
                            request.locationId(),
                            Order.SOONEST,
                            request.weatherRanking().scoringValue());
        }
        ProductRequestParser.IgnoredFields ignoredFields = request.ignoredFields();
        return request.weatherRanking() == null
                ? opportunitySearchService.search(
                        null,
                        request.locationId(),
                        Order.SOONEST,
                        request.preferences(),
                        ignoredFields.paths(),
                        ignoredFields.count())
                : opportunitySearchService.search(
                        null,
                        request.locationId(),
                        Order.SOONEST,
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

    private HttpHeaders successHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(ICALENDAR_MEDIA_TYPE);
        headers.setCacheControl(PRIVATE_CACHE_CONTROL);
        return headers;
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
}
