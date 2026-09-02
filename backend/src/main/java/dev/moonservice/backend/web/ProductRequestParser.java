package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.scoringprototype.UsageException;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AltitudeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AmbientLight;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AzimuthPreference;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.DegreeRange;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.LocalClockWindow;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimeMode;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.TimePreference;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ProductRequestParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpportunitySearchController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BODY_LIMIT_BYTES = 16_384;
    private static final int IGNORED_FIELD_LIMIT = 20;
    private static final int LOCATION_ID_LIMIT = 100;
    private static final Set<String> OPPORTUNITY_FIELDS = Set.of("q", "locationId", "preferences", "weatherRanking");
    private static final Set<String> PLANNING_FIELDS = Set.of("locationId", "preferences");

    private ProductRequestParser() {
    }

    static OpportunityRequest parseOpportunity(HttpServletRequest request) {
        JsonNode root = requestObject(request);
        rejectUnknownFields(root, OPPORTUNITY_FIELDS);

        boolean hasQuery = root.has("q");
        boolean hasLocationId = root.has("locationId");
        if (hasQuery == hasLocationId) {
            throw invalid(hasQuery ? "Use q or locationId, not both." : "q or locationId is required.");
        }
        String query = hasQuery ? requiredText(root, "q") : null;
        String locationId = hasLocationId ? requiredText(root, "locationId") : null;
        ParsedPreferences parsedPreferences = root.has("preferences")
                ? parsePreferences(requiredObject(root, "preferences"))
                : ParsedPreferences.empty();
        return new OpportunityRequest(
                query,
                locationId,
                parsedPreferences.preferences(),
                parsedPreferences.ignoredFields(),
                ProductWeatherRanking.parseOptional(root));
    }

    static PlanningRequest parsePlanning(HttpServletRequest request) {
        JsonNode root = requestObject(request);
        rejectUnknownFields(root, PLANNING_FIELDS);
        if (!root.has("locationId")) {
            throw invalid("locationId is required.");
        }
        if (!root.has("preferences")) {
            throw invalid("preferences is required.");
        }
        String locationId = normalizedLocationId(requiredText(root, "locationId"));
        ParsedPreferences parsedPreferences = parsePreferences(requiredObject(root, "preferences"));
        return new PlanningRequest(
                locationId,
                parsedPreferences.preferences(),
                parsedPreferences.ignoredFields());
    }

    static JsonNode requestObject(HttpServletRequest request) {
        if (!isJson(request.getContentType())) {
            throw new UnsupportedOpportunityMediaTypeException();
        }
        JsonNode root = parseJson(readBody(request));
        if (!root.isObject()) {
            throw invalid("Request body must be a JSON object.");
        }
        return root;
    }

    private static byte[] readBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > BODY_LIMIT_BYTES) {
            throw new OpportunityRequestTooLargeException();
        }
        try {
            byte[] body = request.getInputStream().readNBytes(BODY_LIMIT_BYTES + 1);
            if (body.length > BODY_LIMIT_BYTES) {
                throw new OpportunityRequestTooLargeException();
            }
            return body;
        } catch (IOException exception) {
            throw invalid("Request body must be valid JSON.");
        }
    }

    private static JsonNode parseJson(byte[] body) {
        try (JsonParser parser = MAPPER.createParser(body)) {
            JsonNode root = MAPPER.readTree(parser);
            if (root == null || parser.nextToken() != null) {
                throw invalid("Request body must be valid JSON.");
            }
            return root;
        } catch (JacksonException exception) {
            throw invalid("Request body must be valid JSON.");
        }
    }

    static void rejectUnknownFields(JsonNode root, Set<String> allowedFields) {
        if (root.propertyNames().stream().anyMatch(field -> !allowedFields.contains(field))) {
            throw invalid("Request body contains an unknown field.");
        }
    }

    static ParsedPreferences parsePreferences(JsonNode node) {
        int version = requiredInteger(node, "version");
        if (version != OpportunityPreferences.VERSION) {
            throw invalid("preferences.version must be 1.");
        }
        IgnoredFieldCollector ignoredFields = new IgnoredFieldCollector();
        collectUnknown(node, "", Schema.PREFERENCES, ignoredFields);
        OpportunityPreferences preferences;
        try {
            preferences = new OpportunityPreferences(
                    version,
                    altitude(node),
                    azimuth(node),
                    time(node),
                    namedPhases(node),
                    degreeRanges(node, "brightLimbOrientationDegrees"));
        } catch (UsageException exception) {
            throw invalid(exception.getMessage());
        }
        if (ignoredFields.count() > 0) {
            LOGGER.info(
                    "ignored_preference_fields preferenceVersion={} count={} truncated={}",
                    version,
                    ignoredFields.count(),
                    ignoredFields.count() > IGNORED_FIELD_LIMIT);
        }
        return new ParsedPreferences(
                preferences,
                new IgnoredFields(ignoredFields.paths(), ignoredFields.count()));
    }

    private static AltitudeRange altitude(JsonNode preferences) {
        JsonNode node = optionalObject(preferences, "altitudeDegrees");
        return node == null ? null : new AltitudeRange(
                requiredDouble(node, "minimum"),
                requiredDouble(node, "maximum"));
    }

    private static AzimuthPreference azimuth(JsonNode preferences) {
        JsonNode node = optionalObject(preferences, "azimuthDegrees");
        return node == null ? null : new AzimuthPreference(
                degreeRange(node, "included"),
                degreeRange(node, "excluded"));
    }

    private static DegreeRange degreeRange(JsonNode parent, String name) {
        JsonNode node = optionalObject(parent, name);
        return node == null ? null : range(node);
    }

    private static DegreeRange range(JsonNode node) {
        return new DegreeRange(requiredDouble(node, "start"), requiredDouble(node, "end"));
    }

    private static TimePreference time(JsonNode preferences) {
        JsonNode node = optionalObject(preferences, "time");
        if (node == null) {
            return null;
        }
        TimeMode mode = switch (requiredText(node, "mode")) {
            case "local_clock" -> TimeMode.LOCAL_CLOCK;
            case "light_bucket" -> TimeMode.LIGHT_BUCKET;
            default -> throw invalid("preferences.time.mode is invalid.");
        };
        return new TimePreference(mode, clockWindow(node), lightBuckets(node));
    }

    private static LocalClockWindow clockWindow(JsonNode time) {
        JsonNode value = time.get("window");
        if (value == null) {
            return null;
        }
        JsonNode window = requireObject(value, "preferences.time.window");
        return new LocalClockWindow(
                clockTime(requiredText(window, "start")),
                clockTime(requiredText(window, "end")));
    }

    private static LocalTime clockTime(String value) {
        if (!value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) {
            throw invalid("preferences.time window values must use HH:mm.");
        }
        return LocalTime.parse(value);
    }

    private static Set<AmbientLight> lightBuckets(JsonNode time) {
        JsonNode values = optionalArray(time, "buckets");
        if (values == null) {
            return null;
        }
        EnumSet<AmbientLight> result = EnumSet.noneOf(AmbientLight.class);
        for (JsonNode value : values) {
            result.add(switch (requireText(value, "preferences.time.buckets item")) {
                case "daylight" -> AmbientLight.DAYLIGHT;
                case "golden_hour" -> AmbientLight.GOLDEN_HOUR;
                case "civil_twilight" -> AmbientLight.CIVIL_TWILIGHT;
                case "nautical_twilight" -> AmbientLight.NAUTICAL_TWILIGHT;
                case "night" -> AmbientLight.NIGHT;
                default -> throw invalid("preferences.time.buckets contains an invalid value.");
            });
        }
        return result;
    }

    private static Set<NamedPhase> namedPhases(JsonNode preferences) {
        JsonNode values = optionalArray(preferences, "namedPhases");
        if (values == null) {
            return null;
        }
        EnumSet<NamedPhase> result = EnumSet.noneOf(NamedPhase.class);
        for (JsonNode value : values) {
            result.add(switch (requireText(value, "preferences.namedPhases item")) {
                case "new_moon" -> NamedPhase.NEW_MOON;
                case "waxing_crescent" -> NamedPhase.WAXING_CRESCENT;
                case "first_quarter" -> NamedPhase.FIRST_QUARTER;
                case "waxing_gibbous" -> NamedPhase.WAXING_GIBBOUS;
                case "full_moon" -> NamedPhase.FULL_MOON;
                case "waning_gibbous" -> NamedPhase.WANING_GIBBOUS;
                case "last_quarter" -> NamedPhase.LAST_QUARTER;
                case "waning_crescent" -> NamedPhase.WANING_CRESCENT;
                default -> throw invalid("preferences.namedPhases contains an invalid value.");
            });
        }
        return result;
    }

    private static List<DegreeRange> degreeRanges(JsonNode preferences, String name) {
        JsonNode values = optionalArray(preferences, name);
        if (values == null) {
            return null;
        }
        List<DegreeRange> result = new ArrayList<>();
        for (JsonNode value : values) {
            result.add(range(requireObject(value, "preferences." + name + " item")));
        }
        return result;
    }

    private static void collectUnknown(
            JsonNode node, String pointer, Schema schema, IgnoredFieldCollector result
    ) {
        if (schema.members != null && node.isObject()) {
            for (String member : node.propertyNames()) {
                Schema childSchema = schema.members.get(member);
                String childPointer = pointer + "/" + escapePointerToken(member);
                if (childSchema == null) {
                    result.add(childPointer);
                } else {
                    collectUnknown(node.get(member), childPointer, childSchema, result);
                }
            }
        } else if (schema.element != null && node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectUnknown(node.get(index), pointer + "/" + index, schema.element, result);
            }
        }
    }

    private static String escapePointerToken(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static JsonNode optionalObject(JsonNode parent, String name) {
        if (!parent.has(name)) {
            return null;
        }
        return requireObject(parent.get(name), "preferences." + name);
    }

    static JsonNode requiredObject(JsonNode parent, String name) {
        return requireObject(parent.get(name), name);
    }

    private static JsonNode requireObject(JsonNode value, String name) {
        if (value == null || !value.isObject()) {
            throw invalid(name + " must be an object.");
        }
        return value;
    }

    private static JsonNode optionalArray(JsonNode parent, String name) {
        if (!parent.has(name)) {
            return null;
        }
        JsonNode value = parent.get(name);
        if (!value.isArray()) {
            throw invalid("preferences." + name + " must be an array.");
        }
        return value;
    }

    static String requiredText(JsonNode parent, String name) {
        return requireText(parent.get(name), name);
    }

    private static String requireText(JsonNode value, String name) {
        if (value == null || !value.isString()) {
            throw invalid(name + " must be a string.");
        }
        return value.asString();
    }

    private static int requiredInteger(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid("preferences." + name + " must be an integer.");
        }
        return value.intValue();
    }

    private static double requiredDouble(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isNumber()) {
            throw invalid(name + " must be a number.");
        }
        return value.doubleValue();
    }

    static String normalizedLocationId(String rawLocationId) {
        String locationId = rawLocationId.strip();
        if (locationId.isBlank()) {
            throw invalid("locationId must be non-empty.");
        }
        if (containsUnsupportedControlCharacter(locationId)) {
            throw invalid("locationId contains unsupported control characters.");
        }
        if (locationId.codePointCount(0, locationId.length()) > LOCATION_ID_LIMIT) {
            throw invalid("locationId must be 100 characters or fewer.");
        }
        return locationId;
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

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return "application".equalsIgnoreCase(mediaType.getType())
                    && "json".equalsIgnoreCase(mediaType.getSubtype());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static InvalidOpportunitySearchRequestException invalid(String message) {
        return new InvalidOpportunitySearchRequestException(message);
    }

    record OpportunityRequest(String query, String locationId, OpportunityPreferences preferences,
                              IgnoredFields ignoredFields, ProductWeatherRanking weatherRanking) {
    }

    record PlanningRequest(String locationId, OpportunityPreferences preferences,
                           IgnoredFields ignoredFields) {
    }

    record IgnoredFields(List<String> paths, int count) {
    }

    record ParsedPreferences(OpportunityPreferences preferences, IgnoredFields ignoredFields) {
        private static ParsedPreferences empty() {
            return new ParsedPreferences(null, new IgnoredFields(List.of(), 0));
        }
    }

    private static final class IgnoredFieldCollector {
        private final List<String> paths = new ArrayList<>();
        private int count;

        private void add(String path) {
            count++;
            if (paths.size() < IGNORED_FIELD_LIMIT) {
                paths.add(path);
            }
        }

        private List<String> paths() {
            return List.copyOf(paths);
        }

        private int count() {
            return count;
        }
    }

    private enum Schema {
        SCALAR(null, null),
        RANGE(Map.of("start", SCALAR, "end", SCALAR), null),
        ALTITUDE(Map.of("minimum", SCALAR, "maximum", SCALAR), null),
        CLOCK_WINDOW(Map.of("start", SCALAR, "end", SCALAR), null),
        SCALAR_ARRAY(null, SCALAR),
        RANGE_ARRAY(null, RANGE),
        AZIMUTH(Map.of("included", RANGE, "excluded", RANGE), null),
        TIME(Map.of("mode", SCALAR, "window", CLOCK_WINDOW, "buckets", SCALAR_ARRAY), null),
        PREFERENCES(Map.of(
                "version", SCALAR,
                "altitudeDegrees", ALTITUDE,
                "azimuthDegrees", AZIMUTH,
                "time", TIME,
                "namedPhases", SCALAR_ARRAY,
                "brightLimbOrientationDegrees", RANGE_ARRAY), null);

        private final Map<String, Schema> members;
        private final Schema element;

        Schema(Map<String, Schema> members, Schema element) {
            this.members = members;
            this.element = element;
        }
    }
}
