package dev.moonservice.backend.feedback;

import dev.moonservice.backend.feedback.CalibrationFeedbackReport.AmbientLight;
import dev.moonservice.backend.feedback.CalibrationFeedbackReport.CrescentVisibility;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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
