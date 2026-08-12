package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@RestController
final class AtomFeedController {
    private static final MediaType ATOM_MEDIA_TYPE =
            new MediaType("application", "atom+xml", StandardCharsets.UTF_8);
    private static final String SUCCESS_CACHE_CONTROL = "public, max-age=900";

    private final AtomFeedService atomFeedService;
    private final Clock clock;

    AtomFeedController(AtomFeedService atomFeedService, Clock clock) {
        this.atomFeedService = Objects.requireNonNull(atomFeedService, "atomFeedService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping(value = "/feeds/atom", produces = "application/atom+xml;charset=UTF-8")
    ResponseEntity<?> feed(
            @RequestParam(name = "locationId", required = false) String locationId,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpServletRequest request
    ) {
        boolean head = "HEAD".equals(request.getMethod());
        try {
            AtomFeedService.AtomFeed feed = atomFeedService.feed(locationId);
            if (etagMatches(ifNoneMatch, feed.etag())) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                        .headers(successHeaders(feed))
                        .build();
            }
            if (head) {
                return ResponseEntity.ok()
                        .headers(successHeaders(feed))
                        .build();
            }
            return ResponseEntity.ok()
                    .headers(successHeaders(feed))
                    .body(feed.xml());
        } catch (InvalidOpportunitySearchRequestException ex) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", ex.getMessage(), head);
        } catch (AtomFeedService.FeedFailure ex) {
            return error(ex.httpStatus(), ex.status(), ex.getMessage(), head);
        } catch (RuntimeException ex) {
            return error(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "temporarily_unavailable",
                    "Moon opportunities are temporarily unavailable.",
                    head);
        }
    }

    private HttpHeaders successHeaders(AtomFeedService.AtomFeed feed) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(ATOM_MEDIA_TYPE);
        headers.setCacheControl(SUCCESS_CACHE_CONTROL);
        headers.setETag(feed.etag());
        headers.setContentLength(feed.xml().length);
        return headers;
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

    private static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String value = candidate.strip();
            if ("*".equals(value) || etag.equals(value) || ("W/" + etag).equals(value)) {
                return true;
            }
        }
        return false;
    }
}
