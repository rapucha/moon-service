package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest.Order;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AmbientLight;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.DegreeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimeMode;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class PublicPreferenceQuery {
    private static final int LOCATION_ID_LIMIT = 100;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> CALENDAR_PARAMETERS =
            Set.of("locationId", "order", "weatherRanking", "preferences");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private PublicPreferenceQuery() {
    }

    static CalendarRequest parseCalendar(HttpServletRequest request) {
        Map<String, String[]> parameters = request.getParameterMap();
        if (parameters.keySet().stream().anyMatch(name -> !CALENDAR_PARAMETERS.contains(name))) {
            throw invalid("Request contains an unknown query parameter.");
        }
        for (String[] values : parameters.values()) {
            if (values == null || values.length != 1) {
                throw invalid("Query parameters must not be repeated.");
            }
        }

        String locationId = parameter(parameters, "locationId");
        if (locationId == null) {
            throw invalid("locationId is required.");
        }
        locationId = calendarLocationId(locationId);
        Order order = Order.fromProductQuery(parameter(parameters, "order"));
        ProductWeatherRanking weatherRanking = weatherRanking(parameter(parameters, "weatherRanking"));
        String rawPreferences = parameter(parameters, "preferences");
        ProductRequestParser.ParsedPreferences parsedPreferences = rawPreferences == null
                ? null
                : ProductRequestParser.parsePreferences(preferenceObject(rawPreferences));
        return new CalendarRequest(
                locationId,
                order,
                weatherRanking,
                parsedPreferences == null ? null : parsedPreferences.preferences(),
                parsedPreferences == null
                        ? new ProductRequestParser.IgnoredFields(List.of(), 0)
                        : parsedPreferences.ignoredFields());
    }

    static String calendarQuery(
            String canonicalLocationId,
            Order order,
            ProductWeatherRanking weatherRanking,
            OpportunityPreferences preferences
    ) {
        StringBuilder query = new StringBuilder("?locationId=")
                .append(encode(Objects.requireNonNull(canonicalLocationId, "canonicalLocationId")));
        if (Objects.requireNonNull(order, "order") == Order.SOONEST) {
            query.append("&order=soonest");
        }
        if (weatherRanking != null && weatherRanking != ProductWeatherRanking.BALANCED) {
            query.append("&weatherRanking=").append(weatherRanking.scoringValue().wireValue());
        }
        if (preferences != null && preferences.active()) {
            query.append("&preferences=").append(encode(canonicalPreferences(preferences)));
        }
        return query.toString();
    }

    private static String parameter(Map<String, String[]> parameters, String name) {
        String[] values = parameters.get(name);
        return values == null ? null : values[0];
    }

    private static String calendarLocationId(String rawLocationId) {
        if (rawLocationId.codePointCount(0, rawLocationId.length()) > LOCATION_ID_LIMIT) {
            throw invalid("locationId must be 100 characters or fewer.");
        }
        if (rawLocationId.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint)
                        || codePoint == 0x061C
                        || codePoint == 0x200E
                        || codePoint == 0x200F
                        || codePoint >= 0x202A && codePoint <= 0x202E
                        || codePoint >= 0x2066 && codePoint <= 0x2069)) {
            throw invalid("locationId contains unsupported control characters.");
        }
        return ProductRequestParser.normalizedLocationId(rawLocationId);
    }

    private static ProductWeatherRanking weatherRanking(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "balanced" -> ProductWeatherRanking.BALANCED;
            case "prefer_clear" -> ProductWeatherRanking.PREFER_CLEAR;
            case "ignore_weather" -> ProductWeatherRanking.IGNORE_WEATHER;
            default -> throw invalid(
                    "weatherRanking must be balanced, prefer_clear, or ignore_weather.");
        };
    }

    private static JsonNode preferenceObject(String value) {
        try (JsonParser parser = MAPPER.createParser(value.getBytes(StandardCharsets.UTF_8))) {
            JsonNode root = MAPPER.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw invalid("preferences must be one JSON object.");
            }
            return root;
        } catch (JacksonException exception) {
            throw invalid("preferences must be valid JSON.");
        }
    }

    private static String canonicalPreferences(OpportunityPreferences preferences) {
        StringBuilder json = new StringBuilder("{\"version\":1");
        if (preferences.altitudeDegrees() != null) {
            json.append(",\"altitudeDegrees\":{\"minimum\":")
                    .append(number(preferences.altitudeDegrees().minimum()))
                    .append(",\"maximum\":")
                    .append(number(preferences.altitudeDegrees().maximum()))
                    .append('}');
        }
        if (preferences.azimuthDegrees() != null) {
            json.append(",\"azimuthDegrees\":{");
            boolean separator = false;
            if (preferences.azimuthDegrees().included() != null) {
                json.append("\"included\":");
                appendRange(json, preferences.azimuthDegrees().included());
                separator = true;
            }
            if (preferences.azimuthDegrees().excluded() != null) {
                if (separator) {
                    json.append(',');
                }
                json.append("\"excluded\":");
                appendRange(json, preferences.azimuthDegrees().excluded());
            }
            json.append('}');
        }
        if (preferences.time() != null) {
            json.append(",\"time\":{\"mode\":\"")
                    .append(preferences.time().mode().wireValue())
                    .append('\"');
            if (preferences.time().mode() == TimeMode.LOCAL_CLOCK) {
                json.append(",\"window\":{\"start\":\"")
                        .append(CLOCK_FORMAT.format(preferences.time().localClockWindow().start()))
                        .append("\",\"end\":\"")
                        .append(CLOCK_FORMAT.format(preferences.time().localClockWindow().end()))
                        .append("\"}");
            } else {
                json.append(",\"buckets\":[");
                appendEnumValues(
                        json,
                        Arrays.stream(AmbientLight.values())
                                .filter(preferences.time().lightBuckets()::contains)
                                .map(AmbientLight::wireValue)
                                .toList());
                json.append(']');
            }
            json.append('}');
        }
        if (preferences.namedPhases() != null) {
            json.append(",\"namedPhases\":[");
            appendEnumValues(
                    json,
                    Arrays.stream(NamedPhase.values())
                            .filter(preferences.namedPhases()::contains)
                            .map(NamedPhase::wireValue)
                            .toList());
            json.append(']');
        }
        if (preferences.brightLimbOrientationDegrees() != null) {
            json.append(",\"brightLimbOrientationDegrees\":[");
            List<CanonicalRange> ranges = preferences.brightLimbOrientationDegrees().stream()
                    .map(CanonicalRange::from)
                    .sorted(Comparator.comparingDouble(CanonicalRange::start)
                            .thenComparingDouble(CanonicalRange::end))
                    .distinct()
                    .toList();
            for (int index = 0; index < ranges.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                appendRange(json, ranges.get(index));
            }
            json.append(']');
        }
        return json.append('}').toString();
    }

    private static void appendEnumValues(StringBuilder json, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('\"').append(values.get(index)).append('\"');
        }
    }

    private static void appendRange(StringBuilder json, DegreeRange range) {
        appendRange(json, new CanonicalRange(normalizeZero(range.start()), normalizeZero(range.end())));
    }

    private static void appendRange(StringBuilder json, CanonicalRange range) {
        json.append("{\"start\":")
                .append(number(range.start()))
                .append(",\"end\":")
                .append(number(range.end()))
                .append('}');
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Canonical preference number must be finite");
        }
        if (value == 0.0d) {
            return "0";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static double normalizeZero(double value) {
        return value == 0.0d ? 0.0d : value;
    }

    private static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * 3);
        for (byte rawByte : bytes) {
            int unsigned = rawByte & 0xff;
            if (unsigned >= 'A' && unsigned <= 'Z'
                    || unsigned >= 'a' && unsigned <= 'z'
                    || unsigned >= '0' && unsigned <= '9'
                    || unsigned == '-' || unsigned == '.' || unsigned == '_'
                    || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%')
                        .append(HEX[unsigned >>> 4])
                        .append(HEX[unsigned & 0x0f]);
            }
        }
        return encoded.toString();
    }

    record CalendarRequest(
            String locationId,
            Order order,
            ProductWeatherRanking weatherRanking,
            OpportunityPreferences preferences,
            ProductRequestParser.IgnoredFields ignoredFields
    ) {
    }

    private record CanonicalRange(double start, double end) {
        private static CanonicalRange from(DegreeRange range) {
            return new CanonicalRange(normalizeZero(range.start()), normalizeZero(range.end()));
        }
    }

    private static InvalidOpportunitySearchRequestException invalid(String message) {
        return new InvalidOpportunitySearchRequestException(message);
    }
}
