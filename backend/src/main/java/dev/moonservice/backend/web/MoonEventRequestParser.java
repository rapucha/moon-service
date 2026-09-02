package dev.moonservice.backend.web;

import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class MoonEventRequestParser {
    private static final int DEFAULT_HORIZON_MONTHS = 18;
    private static final List<Integer> SUPPORTED_HORIZON_MONTHS = List.of(6, 12, 18, 24, 36);
    private static final String SUPPORTED_HORIZON_MONTHS_TEXT = SUPPORTED_HORIZON_MONTHS.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(", "));
    private static final Set<String> FIELDS = Set.of(
            "locationId", "preferences", "eventHorizonMonths");

    private MoonEventRequestParser() {
    }

    static MoonEventRequest parse(HttpServletRequest request) {
        JsonNode root = ProductRequestParser.requestObject(request);
        ProductRequestParser.rejectUnknownFields(root, FIELDS);
        if (!root.has("locationId")) {
            throw ProductRequestParser.invalid("locationId is required.");
        }
        if (!root.has("preferences")) {
            throw ProductRequestParser.invalid("preferences is required.");
        }
        String locationId = ProductRequestParser.normalizedLocationId(
                ProductRequestParser.requiredText(root, "locationId"));
        ProductRequestParser.ParsedPreferences parsedPreferences =
                ProductRequestParser.parsePreferences(
                        ProductRequestParser.requiredObject(root, "preferences"));
        return new MoonEventRequest(
                locationId,
                parsedPreferences.preferences(),
                parsedPreferences.ignoredFields(),
                eventHorizonMonths(root));
    }

    private static int eventHorizonMonths(JsonNode root) {
        if (!root.has("eventHorizonMonths")) {
            return DEFAULT_HORIZON_MONTHS;
        }
        JsonNode value = root.get("eventHorizonMonths");
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw ProductRequestParser.invalid("eventHorizonMonths must be an integer.");
        }
        return supportedEventHorizonMonths(value.intValue());
    }

    static int eventHorizonMonths(String value) {
        if (value == null) {
            throw ProductRequestParser.invalid("eventHorizonMonths is required.");
        }
        int months;
        try {
            if (!value.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            months = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw ProductRequestParser.invalid("eventHorizonMonths must be an integer.");
        }
        return supportedEventHorizonMonths(months);
    }

    private static int supportedEventHorizonMonths(int months) {
        if (!SUPPORTED_HORIZON_MONTHS.contains(months)) {
            throw ProductRequestParser.invalid(
                    "eventHorizonMonths must be one of " + SUPPORTED_HORIZON_MONTHS_TEXT + ".");
        }
        return months;
    }

    record MoonEventRequest(
            String locationId,
            OpportunityPreferences preferences,
            ProductRequestParser.IgnoredFields ignoredFields,
            int eventHorizonMonths
    ) {
    }
}
