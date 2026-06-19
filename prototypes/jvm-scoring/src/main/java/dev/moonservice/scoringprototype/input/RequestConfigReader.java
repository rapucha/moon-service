package dev.moonservice.scoringprototype.input;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.moonservice.scoringprototype.fixture.Locations;
import dev.moonservice.scoringprototype.UsageException;

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
        String locationId = text(root, "locationId", Locations.PRAGUE.slug());
        LocalDate startDate = PrototypeConfig.parseStartDate(text(root, "start", "2026-06-29"));
        int days = intValue(root, "forecastHorizonDays", PrototypeConfig.DEFAULT_DAYS, 1, 30);
        double maxMoonAltitudeDegrees = doubleValue(
                root,
                "maxMoonAltitudeDegrees",
                PrototypeConfig.DEFAULT_MAX_MOON_ALTITUDE,
                0.0,
                45.0
        );
        int limit = intValue(root, "limit", 10, 1, 100);

        return new PrototypeConfig(
                Locations.requireFixture(locationId),
                startDate,
                days,
                maxMoonAltitudeDegrees,
                limit
        );
    }

    private static String text(JsonNode root, String field, String defaultValue) {
        JsonNode value = root.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        if (!value.isString() || value.asString().isBlank()) {
            throw new UsageException(field + " must be a non-empty string in the request fixture.");
        }
        return value.asString();
    }

    private static int intValue(JsonNode root, String field, int defaultValue, int min, int max) {
        JsonNode value = root.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        if (!value.canConvertToInt()) {
            throw new UsageException(field + " must be an integer in the request fixture.");
        }
        int parsed = value.asInt();
        if (parsed < min || parsed > max) {
            throw new UsageException(field + " must be between " + min + " and " + max + ".");
        }
        return parsed;
    }

    private static double doubleValue(JsonNode root, String field, double defaultValue, double min, double max) {
        JsonNode value = root.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        if (!value.isNumber()) {
            throw new UsageException(field + " must be numeric in the request fixture.");
        }
        double parsed = value.asDouble();
        if (!Double.isFinite(parsed) || parsed < min || parsed > max) {
            throw new UsageException(field + " must be between " + min + " and " + max + ".");
        }
        return parsed;
    }
}
