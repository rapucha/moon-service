package dev.moonservice.backend.feedback;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record CalibrationFeedbackReport(
        int schemaVersion,
        UUID clientSubmissionId,
        String opportunityId,
        CanonicalLocation location,
        AmbientLight ambientLight,
        CrescentVisibility crescentVisibility,
        String notes,
        String astronomySnapshot,
        String applicationRevision,
        byte[] idempotencyHash,
        Instant submittedAt
) {
    public CalibrationFeedbackReport {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Calibration feedback schema version must be 1.");
        }
        requireUuidV4(clientSubmissionId, "clientSubmissionId");
        requireOpportunityId(opportunityId);
        Objects.requireNonNull(location, "location");
        notes = requireNormalizedNotes(notes);
        if (ambientLight == null && crescentVisibility == null && notes == null) {
            throw new IllegalArgumentException("At least one feedback field must be present.");
        }
        requireText(astronomySnapshot, "astronomySnapshot");
        requireText(applicationRevision, "applicationRevision");
        if (idempotencyHash == null || idempotencyHash.length != 32) {
            throw new IllegalArgumentException("idempotencyHash must contain 32 bytes.");
        }
        idempotencyHash = idempotencyHash.clone();
        Objects.requireNonNull(submittedAt, "submittedAt");
        submittedAt = submittedAt.truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public byte[] idempotencyHash() {
        return idempotencyHash.clone();
    }

    public enum AmbientLight implements WireValue {
        GOOD,
        TOO_BRIGHT,
        TOO_DARK
    }

    public enum CrescentVisibility implements WireValue {
        VISIBLE,
        TOO_SMALL_TO_SEE
    }

    public record CanonicalLocation(
            String id,
            String displayName,
            BigDecimal latitude,
            BigDecimal longitude,
            int elevationMeters,
            ZoneId timezone,
            String countryCode
    ) {
        public CanonicalLocation {
            requireLocationId(id);
            requireText(displayName, "displayName");
            Objects.requireNonNull(latitude, "latitude");
            Objects.requireNonNull(longitude, "longitude");
            Objects.requireNonNull(timezone, "timezone");
            if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                    || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
                throw new IllegalArgumentException("latitude must be between -90 and 90.");
            }
            if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                    || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
                throw new IllegalArgumentException("longitude must be between -180 and 180.");
            }
            if (countryCode == null || !countryCode.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("countryCode must contain two uppercase ASCII letters.");
            }
        }
    }

    public interface WireValue {
        default String wireValue() {
            return ((Enum<?>) this).name().toLowerCase(Locale.ROOT);
        }
    }

    private static void requireUuidV4(UUID uuid, String field) {
        if (uuid == null || uuid.version() != 4 || uuid.variant() != 2) {
            throw new IllegalArgumentException(field + " must be a UUIDv4.");
        }
    }

    private static void requireOpportunityId(String value) {
        requireText(value, "opportunityId");
        requireWellFormedUtf16(value, "opportunityId");
        if (isUnicodeWhitespace(value.codePointAt(0))
                || isUnicodeWhitespace(value.codePointBefore(value.length()))) {
            throw new IllegalArgumentException("opportunityId must not have outer whitespace.");
        }
        value.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint) || isBidirectionalControl(codePoint)) {
                throw new IllegalArgumentException("opportunityId cannot contain control characters.");
            }
        });
    }

    private static void requireLocationId(String value) {
        requireText(value, "locationId");
        requireWellFormedUtf16(value, "locationId");
        int count = value.codePointCount(0, value.length());
        if (count > 100 || isUnicodeWhitespace(value.codePointAt(0))
                || isUnicodeWhitespace(value.codePointBefore(value.length()))) {
            throw new IllegalArgumentException("locationId must be normalized and contain 1-100 code points.");
        }
        value.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint) || isBidirectionalControl(codePoint)) {
                throw new IllegalArgumentException("locationId cannot contain control characters.");
            }
        });
    }

    private static String requireNormalizedNotes(String value) {
        if (value == null) {
            return null;
        }
        requireWellFormedUtf16(value, "notes");
        if (value.indexOf(0) >= 0) {
            throw new IllegalArgumentException("notes cannot contain U+0000.");
        }
        int count = value.codePointCount(0, value.length());
        if (count < 1 || count > 4_000 || !Normalizer.isNormalized(value, Normalizer.Form.NFC)
                || isUnicodeWhitespace(value.codePointAt(0))
                || isUnicodeWhitespace(value.codePointBefore(value.length()))) {
            throw new IllegalArgumentException("notes must be NFC-normalized, trimmed, and contain 1-4000 code points.");
        }
        return value;
    }

    private static void requireWellFormedUtf16(String value, String field) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException(field + " cannot contain unpaired surrogates.");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(field + " cannot contain unpaired surrogates.");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
    }

    private static boolean isBidirectionalControl(int codePoint) {
        return codePoint == 0x061c || codePoint == 0x200e || codePoint == 0x200f
                || codePoint >= 0x202a && codePoint <= 0x202e
                || codePoint >= 0x2066 && codePoint <= 0x2069;
    }

    private static boolean isUnicodeWhitespace(int codePoint) {
        return codePoint >= 0x0009 && codePoint <= 0x000d
                || codePoint == 0x0020 || codePoint == 0x0085 || codePoint == 0x00a0
                || codePoint == 0x1680 || codePoint >= 0x2000 && codePoint <= 0x200a
                || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202f
                || codePoint == 0x205f || codePoint == 0x3000;
    }
}
