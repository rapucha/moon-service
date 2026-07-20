package dev.moonservice.backend.feedback;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.moonservice.backend.feedback.CalibrationFeedbackReport.AmbientLight;
import dev.moonservice.backend.feedback.CalibrationFeedbackReport.CrescentVisibility;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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

record CalibrationFeedbackSubmission(
        int schemaVersion,
        UUID clientSubmissionId,
        String locationId,
        String opportunityId,
        AmbientLight ambientLight,
        CrescentVisibility crescentVisibility,
        String notes,
        byte[] idempotencyHash
) {
    private static final Pattern UUID_V4 = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Set<String> MEMBERS = Set.of(
            "schemaVersion", "clientSubmissionId", "locationId", "opportunityId",
            "ambientLight", "crescentVisibility", "notes");

    CalibrationFeedbackSubmission {
        idempotencyHash = idempotencyHash.clone();
    }

    @Override
    public byte[] idempotencyHash() {
        return idempotencyHash.clone();
    }

    static CalibrationFeedbackSubmission parse(ObjectMapper mapper, String json) throws Invalid {
        JsonNode root;
        try (JsonParser parser = mapper.createParser(json)) {
            root = mapper.readTree(parser);
            if (root == null || parser.nextToken() != null) {
                throw new Invalid("invalid_json", null);
            }
        } catch (JacksonException exception) {
            throw new Invalid("invalid_json", null);
        }
        if (!root.isObject()) {
            throw new Invalid("invalid_request", null);
        }
        for (String member : root.propertyNames()) {
            if (!MEMBERS.contains(member)) {
                throw new Invalid("invalid_request", null);
            }
            if (root.get(member).isNull()) {
                throw new Invalid("invalid_request", member);
            }
        }

        int schemaVersion = requiredInteger(root, "schemaVersion");
        if (schemaVersion != CalibrationFeedbackReport.REPORT_SCHEMA_VERSION) {
            throw new Invalid("invalid_request", "schemaVersion");
        }
        UUID clientSubmissionId = uuidV4(requiredText(root, "clientSubmissionId"));
        String locationId = normalizeLocationId(requiredText(root, "locationId"));
        String opportunityId = validateOpportunityId(requiredText(root, "opportunityId"));
        AmbientLight ambientLight = optionalAmbientLight(root);
        CrescentVisibility crescentVisibility = optionalCrescentVisibility(root);
        String notes = optionalNotes(root);
        if (ambientLight == null && crescentVisibility == null && notes == null) {
            throw new Invalid("invalid_report", null);
        }

        byte[] hash = CalibrationFeedbackDigest.hash(
                locationId, opportunityId, apiValue(ambientLight), apiValue(crescentVisibility), notes);
        return new CalibrationFeedbackSubmission(
                schemaVersion, clientSubmissionId, locationId, opportunityId,
                ambientLight, crescentVisibility, notes, hash);
    }

    private static int requiredInteger(JsonNode root, String name) throws Invalid {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new Invalid("invalid_request", name);
        }
        return value.intValue();
    }

    private static String requiredText(JsonNode root, String name) throws Invalid {
        JsonNode value = root.get(name);
        if (value == null || !value.isString()) {
            throw new Invalid("invalid_request", name);
        }
        String text = value.asString();
        requireWellFormedUtf16(text, name);
        return text;
    }

    private static UUID uuidV4(String value) throws Invalid {
        if (!UUID_V4.matcher(value).matches()) {
            throw new Invalid("invalid_request", "clientSubmissionId");
        }
        return UUID.fromString(value);
    }

    private static String normalizeLocationId(String value) throws Invalid {
        String normalized = stripUnicodeWhitespace(value);
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints < 1 || codePoints > 100 || containsIdControl(normalized)) {
            throw new Invalid("invalid_request", "locationId");
        }
        return normalized;
    }

    private static String validateOpportunityId(String value) throws Invalid {
        if (value.isEmpty()
                || isUnicodeWhitespace(value.codePointAt(0))
                || isUnicodeWhitespace(value.codePointBefore(value.length()))
                || containsIdControl(value)) {
            throw new Invalid("invalid_request", "opportunityId");
        }
        return value;
    }

    private static AmbientLight optionalAmbientLight(JsonNode root) throws Invalid {
        if (!root.has("ambientLight")) {
            return null;
        }
        return switch (requiredText(root, "ambientLight")) {
            case "good" -> AmbientLight.GOOD;
            case "too_bright" -> AmbientLight.TOO_BRIGHT;
            case "too_dark" -> AmbientLight.TOO_DARK;
            default -> throw new Invalid("invalid_request", "ambientLight");
        };
    }

    private static CrescentVisibility optionalCrescentVisibility(JsonNode root) throws Invalid {
        if (!root.has("crescentVisibility")) {
            return null;
        }
        return switch (requiredText(root, "crescentVisibility")) {
            case "visible" -> CrescentVisibility.VISIBLE;
            case "too_small_to_see" -> CrescentVisibility.TOO_SMALL_TO_SEE;
            default -> throw new Invalid("invalid_request", "crescentVisibility");
        };
    }

    private static String optionalNotes(JsonNode root) throws Invalid {
        if (!root.has("notes")) {
            return null;
        }
        String value = requiredText(root, "notes");
        if (value.indexOf(0) >= 0) {
            throw new Invalid("invalid_request", "notes");
        }
        String normalized = stripUnicodeWhitespace(Normalizer.normalize(value, Normalizer.Form.NFC));
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints == 0) {
            throw new Invalid("invalid_report", "notes");
        }
        if (codePoints > 4_000) {
            throw new Invalid("invalid_request", "notes");
        }
        return normalized;
    }

    private static String stripUnicodeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isUnicodeWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isUnicodeWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    private static void requireWellFormedUtf16(String value, String field) throws Invalid {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new Invalid("invalid_request", field);
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new Invalid("invalid_request", field);
            }
        }
    }

    private static boolean containsIdControl(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || codePoint == 0x061c || codePoint == 0x200e || codePoint == 0x200f
                || codePoint >= 0x202a && codePoint <= 0x202e
                || codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    private static boolean isUnicodeWhitespace(int codePoint) {
        return codePoint >= 0x0009 && codePoint <= 0x000d
                || codePoint == 0x0020 || codePoint == 0x0085 || codePoint == 0x00a0
                || codePoint == 0x1680 || codePoint >= 0x2000 && codePoint <= 0x200a
                || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202f
                || codePoint == 0x205f || codePoint == 0x3000;
    }

    private static String apiValue(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(java.util.Locale.ROOT);
    }

    static final class Invalid extends Exception {
        final String code;
        final String field;

        Invalid(String code, String field) {
            this.code = code;
            this.field = field;
        }
    }
}
