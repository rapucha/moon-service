package dev.moonservice.scoringprototype.input;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.moonservice.scoringprototype.UsageException;
import dev.moonservice.scoringprototype.fixture.Locations;

import java.nio.file.Path;
import java.time.LocalDate;

public final class RequestConfigReader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestConfigReader() {
    }

    public static PrototypeConfig read(Path path) {
        try {
            return fromJson(MAPPER.readTree(path.toFile()));
        } catch (JacksonException jex) {
            throw new UsageException("Unable to read --request file: " + path);
        }
    }

    public static PrototypeConfig fromJson(JsonNode root) {
        if (!root.isObject()) {
            throw new UsageException("Request fixture must be a JSON object.");
        }
        String locationId = text(root, "locationId");
        LocalDate startDate = PrototypeConfig.parseStartDate(text(root, "start"));
        int days = intValue(root, "forecastHorizonDays");
        double maxMoonAltitudeDegrees = maxMoonAltitudeDegrees(root);
        int limit = intValue(root, "limit");

        return new PrototypeConfig(
                Locations.requireFixture(locationId),
                startDate,
                days,
                maxMoonAltitudeDegrees,
                limit
        );
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new UsageException(field + " must be a non-empty string in the request fixture.");
        }
        return value.asString();
    }

    private static int intValue(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.canConvertToInt()) {
            throw new UsageException(field + " must be an integer in the request fixture.");
        }
        return value.asInt();
    }

    private static double maxMoonAltitudeDegrees(JsonNode root) {
        JsonNode value = required(root, "maxMoonAltitudeDegrees");
        if (!value.isNumber()) {
            throw new UsageException("maxMoonAltitudeDegrees must be numeric in the request fixture.");
        }
        return value.asDouble();
    }

    private static JsonNode required(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new UsageException(field + " is required in the request fixture.");
        }
        return value;
    }
}
