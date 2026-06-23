package dev.moonservice.backend.opportunity.search;

import dev.moonservice.scoringprototype.UsageException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

public record OpportunitySearchRequest(
        String locationId,
        LocalDate startDate,
        int forecastHorizonDays,
        double maxMoonAltitudeDegrees,
        int limit
) {
    private static final Pattern ISO_LOCAL_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern UTC_INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T.+Z");

    public OpportunitySearchRequest(
            String locationId,
            String start,
            int forecastHorizonDays,
            double maxMoonAltitudeDegrees,
            int limit
    ) {
        this(locationId, parseIsoLocalDateOrUtcInstantDate(start), forecastHorizonDays, maxMoonAltitudeDegrees, limit);
    }

    public static OpportunitySearchRequest fromJson(JsonNode root) {
        if (!root.isObject()) {
            throw new UsageException("Opportunity search request must be a JSON object.");
        }
        return new OpportunitySearchRequest(
                text(root, "locationId"),
                parseIsoLocalDateOrUtcInstantDate(text(root, "start")),
                intValue(root, "forecastHorizonDays"),
                maxMoonAltitudeDegrees(root),
                intValue(root, "limit")
        );
    }

    public String start() {
        return startDate.toString();
    }

    private static LocalDate parseIsoLocalDateOrUtcInstantDate(String value) {
        if (ISO_LOCAL_DATE.matcher(value).matches()) {
            return parseIsoLocalDate(value);
        }
        if (UTC_INSTANT.matcher(value).matches()) {
            return parseUtcInstantDate(value);
        }
        throw new UsageException("Invalid --start value: " + value);
    }

    private static LocalDate parseIsoLocalDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new UsageException("Invalid --start value: " + value, ex);
        }
    }

    private static LocalDate parseUtcInstantDate(String value) {
        try {
            return Instant.parse(value).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException ex) {
            throw new UsageException("Invalid --start value: " + value, ex);
        }
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
