package dev.moonservice.scoringprototype.input;

import dev.moonservice.scoringprototype.UsageException;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record OpportunityPreferences(
        int version,
        AltitudeRange altitudeDegrees,
        AzimuthPreference azimuthDegrees,
        TimePreference time,
        Set<NamedPhase> namedPhases,
        List<DegreeRange> brightLimbOrientationDegrees
) {
    private static final int MAX_RANGES = 8;
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public OpportunityPreferences {
        if (version != 1) {
            throw invalid("version must be 1.");
        }
        if (namedPhases != null) {
            if (namedPhases.isEmpty() || namedPhases.stream().anyMatch(Objects::isNull)) {
                throw invalid("namedPhases must contain at least one value.");
            }
            namedPhases = Collections.unmodifiableSet(EnumSet.copyOf(namedPhases));
        }
        if (brightLimbOrientationDegrees != null) {
            if (brightLimbOrientationDegrees.stream().anyMatch(Objects::isNull)) {
                throw invalid("brightLimbOrientationDegrees must contain valid ranges.");
            }
            brightLimbOrientationDegrees = List.copyOf(brightLimbOrientationDegrees);
            if (brightLimbOrientationDegrees.isEmpty()) {
                throw invalid("brightLimbOrientationDegrees must contain at least one range.");
            }
            if (brightLimbOrientationDegrees.size() > MAX_RANGES) {
                throw invalid("brightLimbOrientationDegrees allows at most eight ranges.");
            }
        }
    }

    public static OpportunityPreferences none() {
        return new OpportunityPreferences(1, null, null, null, null, null);
    }

    public boolean active() {
        return altitudeDegrees != null
                || azimuthDegrees != null
                || time != null
                || namedPhases != null
                || brightLimbOrientationDegrees != null;
    }

    public Map<String, Object> normalizedFilters() {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (altitudeDegrees != null) {
            filters.put("altitudeDegrees", Map.of(
                    "minimum", altitudeDegrees.minimum(),
                    "maximum", altitudeDegrees.maximum()));
        }
        if (azimuthDegrees != null) {
            Map<String, Object> sectors = new LinkedHashMap<>();
            if (azimuthDegrees.included() != null) {
                sectors.put("included", normalized(azimuthDegrees.included()));
            }
            if (azimuthDegrees.excluded() != null) {
                sectors.put("excluded", normalized(azimuthDegrees.excluded()));
            }
            filters.put("azimuthDegrees", Collections.unmodifiableMap(sectors));
        }
        if (time != null) {
            filters.put("time", time.normalized());
        }
        if (namedPhases != null) {
            filters.put("namedPhases", namedPhases.stream().map(NamedPhase::wireValue).toList());
        }
        if (brightLimbOrientationDegrees != null) {
            filters.put("brightLimbOrientationDegrees",
                    brightLimbOrientationDegrees.stream().map(OpportunityPreferences::normalized).toList());
        }
        return Collections.unmodifiableMap(filters);
    }

    private static Map<String, Double> normalized(DegreeRange range) {
        return Map.of("start", range.start(), "end", range.end());
    }

    public record AltitudeRange(double minimum, double maximum) {
        public AltitudeRange {
            altitudeDegree("altitude minimum", minimum);
            altitudeDegree("altitude maximum", maximum);
            if (minimum > maximum) {
                throw invalid("altitude minimum must not exceed maximum.");
            }
        }
    }

    public record DegreeRange(double start, double end) {
        public DegreeRange {
            degree("range start", start, 360.0);
            degree("range end", end, 360.0);
            if (start == end) {
                throw invalid("directed range endpoints must differ.");
            }
        }

        public boolean contains(double value) {
            double offset = clockwiseDistance(start, normalize(value));
            return offset <= clockwiseDistance(start, end);
        }
    }

    public record AzimuthPreference(DegreeRange included, DegreeRange excluded) {
        public AzimuthPreference {
            if (included == null && excluded == null) {
                throw invalid("azimuthDegrees must contain included or excluded.");
            }
            if (included != null && excluded != null && !contained(included, excluded)) {
                throw invalid("excluded azimuth sector must be contained within included.");
            }
        }

        private static boolean contained(DegreeRange outer, DegreeRange inner) {
            double offset = clockwiseDistance(outer.start(), inner.start());
            return offset + clockwiseDistance(inner.start(), inner.end())
                    <= clockwiseDistance(outer.start(), outer.end());
        }
    }

    public record LocalClockWindow(LocalTime start, LocalTime end) {
        public LocalClockWindow {
            if (start == null || end == null) {
                throw invalid("local clock start and end are required.");
            }
            if (start.getSecond() != 0 || start.getNano() != 0 || end.getSecond() != 0 || end.getNano() != 0) {
                throw invalid("local clock values must use minute precision.");
            }
            if (start.equals(end)) {
                throw invalid("local clock window endpoints must differ.");
            }
        }

        Map<String, String> normalized() {
            return Map.of("start", CLOCK_FORMAT.format(start), "end", CLOCK_FORMAT.format(end));
        }
    }

    public record TimePreference(
            TimeMode mode,
            List<LocalClockWindow> localClockWindows,
            Set<AmbientLight> lightBuckets
    ) {
        public TimePreference {
            if (mode == null) {
                throw invalid("time mode is required.");
            }
            if (localClockWindows != null && localClockWindows.stream().anyMatch(Objects::isNull)) {
                throw invalid("local clock windows must not contain null.");
            }
            if (lightBuckets != null && lightBuckets.stream().anyMatch(Objects::isNull)) {
                throw invalid("light buckets must not contain null.");
            }
            localClockWindows = List.copyOf(localClockWindows == null ? List.of() : localClockWindows);
            lightBuckets = lightBuckets == null || lightBuckets.isEmpty()
                    ? Set.of()
                    : Collections.unmodifiableSet(EnumSet.copyOf(lightBuckets));
            if (mode == TimeMode.LOCAL_CLOCK) {
                if (localClockWindows.isEmpty() || !lightBuckets.isEmpty()) {
                    throw invalid("local_clock mode requires windows and no light buckets.");
                }
                if (localClockWindows.size() > MAX_RANGES) {
                    throw invalid("local_clock mode allows at most eight windows.");
                }
            } else if (lightBuckets.isEmpty() || !localClockWindows.isEmpty()) {
                throw invalid("light_bucket mode requires buckets and no clock windows.");
            }
        }

        Map<String, Object> normalized() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("mode", mode.wireValue());
            if (mode == TimeMode.LOCAL_CLOCK) {
                value.put("windows", localClockWindows.stream().map(LocalClockWindow::normalized).toList());
            } else {
                value.put("buckets", lightBuckets.stream().map(AmbientLight::wireValue).toList());
            }
            return Collections.unmodifiableMap(value);
        }
    }

    public enum TimeMode {
        LOCAL_CLOCK("local_clock"), LIGHT_BUCKET("light_bucket");

        private final String wireValue;

        TimeMode(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public enum AmbientLight {
        DAYLIGHT("daylight"), GOLDEN_HOUR("golden_hour"), CIVIL_TWILIGHT("civil_twilight"),
        NAUTICAL_TWILIGHT("nautical_twilight"), NIGHT("night");

        private final String wireValue;

        AmbientLight(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public enum NamedPhase {
        NEW_MOON("new_moon"), WAXING_CRESCENT("waxing_crescent"), FIRST_QUARTER("first_quarter"),
        WAXING_GIBBOUS("waxing_gibbous"), FULL_MOON("full_moon"), WANING_GIBBOUS("waning_gibbous"),
        LAST_QUARTER("last_quarter"), WANING_CRESCENT("waning_crescent");

        private final String wireValue;

        NamedPhase(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    private static void degree(String field, double value, double upperExclusive) {
        if (!Double.isFinite(value) || value < 0.0 || value >= upperExclusive) {
            throw invalid(field + " is outside its degree range.");
        }
    }

    private static void altitudeDegree(String field, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 90.0) {
            throw invalid(field + " is outside its degree range.");
        }
    }

    private static double normalize(double value) {
        return ((value % 360.0) + 360.0) % 360.0;
    }

    private static double clockwiseDistance(double start, double end) {
        return normalize(end - start);
    }

    private static UsageException invalid(String message) {
        return new UsageException("Invalid opportunity preferences: " + message);
    }
}
