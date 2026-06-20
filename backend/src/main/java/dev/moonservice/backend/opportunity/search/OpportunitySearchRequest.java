package dev.moonservice.backend.opportunity.search;

import dev.moonservice.scoringprototype.UsageException;
import tools.jackson.databind.JsonNode;

public record OpportunitySearchRequest(
        String locationId,
        String start,
        int forecastHorizonDays,
        double maxMoonAltitudeDegrees,
        int limit
) {
    public static OpportunitySearchRequest fromJson(JsonNode root) {
        if (!root.isObject()) {
            throw new UsageException("Opportunity search request must be a JSON object.");
        }
        return new OpportunitySearchRequest(
                text(root, "locationId"),
                text(root, "start"),
                intValue(root, "forecastHorizonDays"),
                maxMoonAltitudeDegrees(root),
                intValue(root, "limit")
        );
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new UsageException(field + " must be a non-empty string in the opportunity search request.");
        }
        return value.asString();
    }

    private static int intValue(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.canConvertToInt()) {
            throw new UsageException(field + " must be an integer in the opportunity search request.");
        }
        return value.asInt();
    }

    private static double maxMoonAltitudeDegrees(JsonNode root) {
        JsonNode value = required(root, "maxMoonAltitudeDegrees");
        if (!value.isNumber()) {
            throw new UsageException("maxMoonAltitudeDegrees must be numeric in the opportunity search request.");
        }
        return value.asDouble();
    }

    private static JsonNode required(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new UsageException(field + " is required in the opportunity search request.");
        }
        return value;
    }
}
