package dev.moonservice.backend.feedback;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@RestController
class CalibrationFeedbackController {
    private static final int SCHEMA_VERSION = CalibrationFeedbackReport.REPORT_SCHEMA_VERSION;
    private static final int BODY_LIMIT_BYTES = 16_384;
    private final CalibrationFeedbackService service;
    private final Clock clock;
    private final ObjectMapper requestMapper;
    private final String applicationRevision;

    CalibrationFeedbackController(
            CalibrationFeedbackService service,
            Clock clock,
            ObjectMapper objectMapper,
            @Value("${moon.build.revision:local}") String applicationRevision
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requestMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        String normalizedRevision = applicationRevision == null ? "" : applicationRevision.strip();
        this.applicationRevision = normalizedRevision.isEmpty() ? "local" : normalizedRevision;
    }

    @GetMapping(value = "/api/calibration-feedback/v1/capability", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CapabilityResponse> capability() {
        CalibrationFeedbackService.Capability capability = service.capability();
        return noStore(HttpStatus.OK).body(new CapabilityResponse(
                SCHEMA_VERSION,
                now(),
                capability.featureState(),
                capability.submissionAvailability()));
    }

    @PostMapping(value = "/api/calibration-feedback/v1/submissions", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> submit(HttpServletRequest request) {
        if (!supportsTransport(request)) {
            return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", null, null, now());
        }
        if (request.getContentLengthLong() > BODY_LIMIT_BYTES) {
            return error(HttpStatus.CONTENT_TOO_LARGE, "request_too_large", null, null, now());
        }

        byte[] body;
        try {
            body = request.getInputStream().readNBytes(BODY_LIMIT_BYTES + 1);
        } catch (IOException exception) {
            return error(HttpStatus.BAD_REQUEST, "invalid_json", null, null, now());
        }
        if (body.length > BODY_LIMIT_BYTES) {
            return error(HttpStatus.CONTENT_TOO_LARGE, "request_too_large", null, null, now());
        }

        Instant receivedAt = now();
        CalibrationFeedbackSubmission submission;
        try {
            submission = CalibrationFeedbackSubmission.parse(requestMapper, decodeUtf8(body));
        } catch (CalibrationFeedbackSubmission.Invalid exception) {
            HttpStatus status = "invalid_report".equals(exception.code)
                    ? HttpStatus.UNPROCESSABLE_CONTENT
                    : HttpStatus.BAD_REQUEST;
            return error(status, exception.code, exception.field, null, receivedAt);
        }

        return responseFor(service.submit(submission, receivedAt, applicationRevision), receivedAt);
    }

    private ResponseEntity<?> responseFor(CalibrationFeedbackService.SubmissionResult result, Instant serverTime) {
        return switch (result) {
            case CalibrationFeedbackService.Created created -> success(
                    HttpStatus.CREATED, "created", created.clientSubmissionId(),
                    created.serverReportId(), created.submittedAt(), serverTime);
            case CalibrationFeedbackService.Replayed replayed -> success(
                    HttpStatus.OK, "replayed", replayed.clientSubmissionId(),
                    replayed.serverReportId(), replayed.submittedAt(), serverTime);
            case CalibrationFeedbackService.Conflict ignored -> error(
                    HttpStatus.CONFLICT, "client_submission_conflict", null, null, serverTime);
            case CalibrationFeedbackService.LocationNotFound ignored -> error(
                    HttpStatus.UNPROCESSABLE_CONTENT, "location_not_found", "locationId", null, serverTime);
            case CalibrationFeedbackService.RateLimited limited -> error(
                    HttpStatus.TOO_MANY_REQUESTS, "rate_limited", null,
                    Math.max(1L, limited.retryAfterSeconds()), serverTime);
            default -> error(HttpStatus.SERVICE_UNAVAILABLE, "feedback_unavailable", null, null, serverTime);
        };
    }

    private ResponseEntity<SubmissionResponse> success(
            HttpStatus status,
            String outcome,
            UUID clientSubmissionId,
            UUID serverReportId,
            Instant submittedAt,
            Instant serverTime
    ) {
        return noStore(status).body(new SubmissionResponse(
                SCHEMA_VERSION, serverTime, outcome, clientSubmissionId, serverReportId, submittedAt));
    }

    private ResponseEntity<ErrorResponse> error(
            HttpStatus status,
            String code,
            String field,
            Long retryAfterSeconds,
            Instant serverTime
    ) {
        ResponseEntity.BodyBuilder response = noStore(status);
        if (retryAfterSeconds != null) {
            response.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString());
        }
        return response.body(new ErrorResponse(
                SCHEMA_VERSION,
                serverTime,
                new ApiError(code, messageFor(code), field, retryAfterSeconds)));
    }

    private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private static boolean supportsTransport(HttpServletRequest request) {
        if (request.getHeader(HttpHeaders.CONTENT_ENCODING) != null || request.getContentType() == null) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(request.getContentType());
            if (!"application".equalsIgnoreCase(mediaType.getType())
                    || !"json".equalsIgnoreCase(mediaType.getSubtype())) {
                return false;
            }
            if (mediaType.getParameters().isEmpty()) {
                return true;
            }
            return mediaType.getParameters().size() == 1
                    && "UTF-8".equalsIgnoreCase(mediaType.getParameter("charset"));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String decodeUtf8(byte[] body) throws CalibrationFeedbackSubmission.Invalid {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new CalibrationFeedbackSubmission.Invalid("invalid_json", null);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String messageFor(String code) {
        return switch (code) {
            case "invalid_json" -> "Request body must contain valid JSON.";
            case "invalid_request" -> "Request body does not match the calibration feedback contract.";
            case "invalid_report" -> "Feedback report does not satisfy the calibration contract.";
            case "request_too_large" -> "Request body exceeds 16,384 bytes.";
            case "unsupported_media_type" -> "Feedback accepts only unencoded UTF-8 application/json.";
            case "location_not_found" -> "The selected location could not be found.";
            case "client_submission_conflict" -> "The submission identifier belongs to different feedback.";
            case "rate_limited" -> "Too many feedback submissions. Please try again later.";
            default -> "Calibration feedback is unavailable.";
        };
    }

    private record CapabilityResponse(
            int schemaVersion,
            Instant serverTime,
            String featureState,
            String submissionAvailability
    ) {
    }

    private record SubmissionResponse(
            int schemaVersion,
            Instant serverTime,
            String status,
            UUID clientSubmissionId,
            UUID serverReportId,
            Instant submittedAt
    ) {
    }

    private record ErrorResponse(int schemaVersion, Instant serverTime, ApiError error) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ApiError(String code, String message, String field, Long retryAfterSeconds) {
    }
}
