package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.OpportunitySearchService;
import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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

@RestController
class OpportunitySearchController {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpportunitySearchController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BODY_LIMIT_BYTES = 16_384;
    private static final int IGNORED_FIELD_LIMIT = 20;
    private static final Set<String> REQUEST_FIELDS = Set.of("q", "locationId", "preferences");

    private final OpportunitySearchService opportunitySearchService;

    OpportunitySearchController(OpportunitySearchService opportunitySearchService) {
        this.opportunitySearchService = opportunitySearchService;
    }

    @GetMapping("/api/opportunities")
    ResponseEntity<OpportunityResponse> searchByQuery(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "locationId", required = false) String locationId
    ) {
        OpportunityResponse response = opportunitySearchService.search(query, locationId);
        return ResponseEntity.status(httpStatusFor(response)).body(response);
    }

    @PostMapping(value = "/api/opportunities", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OpportunityResponse> searchWithPreferences(HttpServletRequest request) {
        if (!isJson(request.getContentType())) {
            throw new UnsupportedOpportunityMediaTypeException();
        }
        ProductRequest productRequest = parse(readBody(request));
        OpportunityResponse response = productRequest.preferences() == null
                ? opportunitySearchService.search(productRequest.query(), productRequest.locationId())
                : opportunitySearchService.search(
                        productRequest.query(),
                        productRequest.locationId(),
                        productRequest.preferences(),
                        productRequest.ignoredFields().paths(),
                        productRequest.ignoredFields().count());
        return ResponseEntity.status(httpStatusFor(response))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    @PostMapping("/api/opportunities/search")
    OpportunitySearchResponse search(@RequestBody JsonNode request) {
        return opportunitySearchService.search(request);
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

    private static ProductRequest parse(byte[] body) {
        JsonNode root = parseJson(body);
        if (!root.isObject()) {
            throw invalid("Request body must be a JSON object.");
        }
        for (String field : root.propertyNames()) {
            if (!REQUEST_FIELDS.contains(field)) {
                throw invalid("Request body contains an unknown field.");
            }
        }

        boolean hasQuery = root.has("q");
        boolean hasLocationId = root.has("locationId");
        if (hasQuery == hasLocationId) {
            throw invalid(hasQuery ? "Use q or locationId, not both." : "q or locationId is required.");
        }
        String query = hasQuery ? requiredText(root, "q") : null;
        String locationId = hasLocationId ? requiredText(root, "locationId") : null;
        if (!root.has("preferences")) {
            return new ProductRequest(query, locationId, null, null);
        }

        JsonNode preferencesNode = requiredObject(root, "preferences");
        int version = requiredInteger(preferencesNode, "version");
        if (version != OpportunityPreferences.VERSION) {
            throw invalid("preferences.version must be 1.");
        }
        IgnoredFields ignoredFields = ignoredFields(preferencesNode);
        OpportunityPreferences preferences = parsePreferences(preferencesNode, version);
        if (ignoredFields.count() > 0) {
            LOGGER.info(
                    "ignored_preference_fields preferenceVersion={} count={} truncated={}",
                    version,
                    ignoredFields.count(),
                    ignoredFields.count() > IGNORED_FIELD_LIMIT);
        }
        return new ProductRequest(query, locationId, preferences, ignoredFields);
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

    private static OpportunityPreferences parsePreferences(JsonNode node, int version) {
        try {
            return new OpportunityPreferences(
                    version,
                    altitude(node),
                    azimuth(node),
                    time(node),
                    namedPhases(node),
                    degreeRanges(node, "brightLimbOrientationDegrees"));
        } catch (UsageException exception) {
            throw invalid(exception.getMessage());
        }
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
        return new TimePreference(mode, clockWindows(node), lightBuckets(node));
    }

    private static List<LocalClockWindow> clockWindows(JsonNode time) {
        JsonNode values = optionalArray(time, "windows");
        if (values == null) {
            return null;
        }
        List<LocalClockWindow> result = new ArrayList<>();
        for (JsonNode value : values) {
            JsonNode window = requireObject(value, "preferences.time.windows item");
            result.add(new LocalClockWindow(
                    clockTime(requiredText(window, "start")),
                    clockTime(requiredText(window, "end"))));
        }
        return result;
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

    private static IgnoredFields ignoredFields(JsonNode preferences) {
        IgnoredFields fields = new IgnoredFields();
        collectUnknown(preferences, "", Schema.PREFERENCES, fields);
        return fields;
    }

    private static void collectUnknown(JsonNode node, String pointer, Schema schema, IgnoredFields result) {
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

    private static JsonNode requiredObject(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        return requireObject(value, name);
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

    private static String requiredText(JsonNode parent, String name) {
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

    private static InvalidOpportunitySearchRequestException invalid(String message) {
        return new InvalidOpportunitySearchRequestException(message);
    }

    private static HttpStatus httpStatusFor(OpportunityResponse response) {
        if ("temporarily_unavailable".equals(response.status())) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.OK;
    }

    private record ProductRequest(
            String query,
            String locationId,
            OpportunityPreferences preferences,
            IgnoredFields ignoredFields
    ) {
    }

    private static final class IgnoredFields {
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
        CLOCK_WINDOW_ARRAY(null, CLOCK_WINDOW),
        AZIMUTH(Map.of("included", RANGE, "excluded", RANGE), null),
        TIME(Map.of("mode", SCALAR, "windows", CLOCK_WINDOW_ARRAY, "buckets", SCALAR_ARRAY), null),
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
