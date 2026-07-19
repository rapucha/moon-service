package dev.moonservice.backend.feedback;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record CalibrationFeedbackReport(
        int schemaVersion,
        UUID clientSubmissionId,
        ReportMode mode,
        NormalizedTiming timing,
        CanonicalLocation location,
        Ratings ratings,
        String notes,
        String recommendationSnapshot,
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
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(timing, "timing");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(ratings, "ratings");
        Objects.requireNonNull(submittedAt, "submittedAt");
        if (timing.kind() == TimingKind.NOW && !submittedAt.equals(timing.resolvedAt())) {
            throw new IllegalArgumentException("Now timing and submission must use one receipt instant.");
        }
        submittedAt = submittedAt.truncatedTo(ChronoUnit.MICROS);
        requireNormalizedNotes(notes);
        requireText(astronomySnapshot, "astronomySnapshot");
        requireText(applicationRevision, "applicationRevision");
        if (!timing.timezone().equals(location.timezone())) {
            throw new IllegalArgumentException("Timing timezone must match the canonical location timezone.");
        }
        if (mode == ReportMode.RECOMMENDATION_REVIEW) {
            requireText(recommendationSnapshot, "recommendationSnapshot");
        } else if (recommendationSnapshot != null) {
            throw new IllegalArgumentException("Observation reports cannot contain a recommendation snapshot.");
        }
        if (mode == ReportMode.OBSERVATION && ratings.weather() != WeatherRating.NOT_COMPARED) {
            throw new IllegalArgumentException("Observation weather rating must be not_compared.");
        }
        if (idempotencyHash == null || idempotencyHash.length != 32) {
            throw new IllegalArgumentException("idempotencyHash must contain 32 bytes.");
        }
        idempotencyHash = idempotencyHash.clone();
    }

    @Override
    public byte[] idempotencyHash() {
        return idempotencyHash.clone();
    }

    public enum ReportMode implements WireValue {
        RECOMMENDATION_REVIEW,
        OBSERVATION
    }

    public enum TimingKind implements WireValue {
        NOW,
        PAST
    }

    public enum TimingSource implements WireValue {
        SERVER_RECEIPT,
        CAMERA_METADATA,
        PHONE_METADATA,
        WRITTEN_RECORD,
        MEMORY,
        OTHER
    }

    public enum TimingConfidence implements WireValue {
        EXACT,
        WITHIN_5_MINUTES,
        WITHIN_30_MINUTES,
        WITHIN_2_HOURS,
        DATE_ONLY
    }

    public enum OverallRating implements WireValue {
        POSITIVE,
        MARGINAL,
        NEGATIVE
    }

    public enum MoonRating implements WireValue {
        CLEAR,
        PARTIAL,
        NOT_VISIBLE,
        UNKNOWN
    }

    public enum AmbientLightRating implements WireValue {
        SUFFICIENT,
        MARGINAL,
        INSUFFICIENT,
        UNKNOWN
    }

    public enum WeatherRating implements WireValue {
        BETTER,
        MATCHED,
        WORSE,
        NOT_COMPARED
    }

    public enum HorizonRating implements WireValue {
        NONE,
        MINOR,
        BLOCKED,
        UNKNOWN
    }

    public record NormalizedTiming(
            TimingKind kind,
            LocalDateTime enteredLocalDateTime,
            LocalDateTime correctedLocalDateTime,
            LocalDateTime resolvedLocalDateTime,
            ZoneId timezone,
            ZoneOffset utcOffset,
            TimingSource source,
            TimingConfidence confidence,
            Instant resolvedAt
    ) {
        public NormalizedTiming {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(resolvedLocalDateTime, "resolvedLocalDateTime");
            Objects.requireNonNull(timezone, "timezone");
            Objects.requireNonNull(utcOffset, "utcOffset");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(resolvedAt, "resolvedAt");
            requireWholeSecond(resolvedLocalDateTime, "resolvedLocalDateTime");
            if (!timezone.getRules().getOffset(resolvedAt).equals(utcOffset)) {
                throw new IllegalArgumentException("utcOffset must match the timezone at resolvedAt.");
            }
            if (!LocalDateTime.ofInstant(resolvedAt, timezone).equals(resolvedLocalDateTime)) {
                throw new IllegalArgumentException("resolvedLocalDateTime must describe resolvedAt in the timezone.");
            }
            if (kind == TimingKind.NOW) {
                if (enteredLocalDateTime != null || correctedLocalDateTime != null
                        || source != TimingSource.SERVER_RECEIPT || confidence != TimingConfidence.EXACT) {
                    throw new IllegalArgumentException("Now timing must use only exact server receipt timing.");
                }
            } else {
                requireWholeSecond(enteredLocalDateTime, "enteredLocalDateTime");
                requireWholeSecond(correctedLocalDateTime, "correctedLocalDateTime");
                if (!correctedLocalDateTime.equals(resolvedLocalDateTime)) {
                    throw new IllegalArgumentException("Corrected and resolved local time must match.");
                }
                if (source == TimingSource.SERVER_RECEIPT) {
                    throw new IllegalArgumentException("Past timing cannot use server_receipt as its source.");
                }
            }
        }
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

    public record Ratings(
            OverallRating overall,
            MoonRating moon,
            AmbientLightRating ambientLight,
            WeatherRating weather,
            HorizonRating horizon
    ) {
        public Ratings {
            Objects.requireNonNull(overall, "overall");
            Objects.requireNonNull(moon, "moon");
            Objects.requireNonNull(ambientLight, "ambientLight");
            Objects.requireNonNull(weather, "weather");
            Objects.requireNonNull(horizon, "horizon");
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

    private static void requireWholeSecond(LocalDateTime value, String field) {
        Objects.requireNonNull(value, field);
        if (value.getYear() < 1 || value.getYear() > 9999 || value.getNano() != 0) {
            throw new IllegalArgumentException(field + " must use years 0001-9999 and whole seconds.");
        }
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

    private static void requireNormalizedNotes(String value) {
        requireText(value, "notes");
        requireWellFormedUtf16(value, "notes");
        int count = value.codePointCount(0, value.length());
        if (count < 10 || count > 4_000 || !Normalizer.isNormalized(value, Normalizer.Form.NFC)
                || isUnicodeWhitespace(value.codePointAt(0))
                || isUnicodeWhitespace(value.codePointBefore(value.length()))) {
            throw new IllegalArgumentException("notes must be normalized and contain 10-4000 code points.");
        }
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
