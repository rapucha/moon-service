package dev.moonservice.backend.opportunity.scoring;

import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class PrototypeOpportunityResponseMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PrototypeOpportunityResponseMapper() {
    }

    static OpportunitySearchResponse map(String prototypeJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(prototypeJson);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Prototype opportunity response was not valid JSON.", ex);
        }

        return new OpportunitySearchResponse(
                text(root, "status"),
                Instant.now().toString(),
                location(root.path("location")),
                intValue(root, "forecastHorizonDays"),
                text(root, "startsAt"),
                text(root, "endsAt"),
                intValue(root, "candidateWindowsEvaluated"),
                doubleValue(root, "maxMoonAltitudeDegrees"),
                opportunities(root.path("opportunities")),
                rejected(root.path("rejected")),
                messages(root.path("messages"))
        );
    }

    private static OpportunitySearchResponse.Location location(JsonNode node) {
        return new OpportunitySearchResponse.Location(
                text(node, "id"),
                text(node, "kind"),
                text(node, "displayName"),
                doubleValue(node, "latitude"),
                doubleValue(node, "longitude"),
                intValue(node, "elevationMeters"),
                text(node, "timezone"),
                text(node, "countryCode")
        );
    }

    private static ArrayList<OpportunitySearchResponse.Opportunity> opportunities(JsonNode nodes) {
        ArrayList<OpportunitySearchResponse.Opportunity> opportunities = new ArrayList<>();
        for (JsonNode node : nodes) {
            opportunities.add(new OpportunitySearchResponse.Opportunity(
                    text(node, "id"),
                    text(node, "windowKind"),
                    moonPass(node.path("moonPass")),
                    text(node, "startsAt"),
                    text(node, "suggestedAt"),
                    text(node, "endsAt"),
                    text(node, "localTimeZone"),
                    intValue(node, "score"),
                    text(node, "confidence"),
                    componentScores(node.path("components")),
                    scoreBasis(node.path("scoreBasis")),
                    moon(node.path("moon")),
                    moonPath(node.path("moonPath")),
                    sun(node.path("sun")),
                    weather(node.path("weather")),
                    exposureBalance(node.path("exposureBalance")),
                    text(node, "reason"),
                    Map.of("ics", text(node.path("links"), "ics"))
            ));
        }
        return opportunities;
    }

    private static OpportunitySearchResponse.MoonPass moonPass(JsonNode node) {
        return new OpportunitySearchResponse.MoonPass(
                text(node, "id"),
                text(node, "startsAt"),
                text(node, "endsAt"),
                moonPassPath(node.path("path"))
        );
    }

    private static OpportunitySearchResponse.MoonPassPath moonPassPath(JsonNode node) {
        return new OpportunitySearchResponse.MoonPassPath(
                moonPathPoint(node.path("start")),
                moonPathPoint(node.path("end")),
                moonPathSamples(node.path("samples"))
        );
    }

    private static OpportunitySearchResponse.ComponentScores componentScores(JsonNode node) {
        return new OpportunitySearchResponse.ComponentScores(
                intValue(node, "moonAltitudeFit"),
                intValue(node, "sunLightFit"),
                intValue(node, "moonIlluminationFit"),
                nullableInteger(node, "weatherFit"),
                nullableInteger(node, "forecastConfidence")
        );
    }

    private static OpportunitySearchResponse.ScoreBasis scoreBasis(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        List<String> excludedComponents = new ArrayList<>();
        for (JsonNode value : node.path("excludedComponents")) {
            excludedComponents.add(value.asString());
        }
        return new OpportunitySearchResponse.ScoreBasis(
                intValue(node, "componentPoints"),
                intValue(node, "componentMaximum"),
                List.copyOf(excludedComponents));
    }

    private static OpportunitySearchResponse.Moon moon(JsonNode node) {
        return new OpportunitySearchResponse.Moon(
                doubleValue(node, "altitudeDegrees"),
                doubleValue(node, "azimuthDegrees"),
                doubleValue(node, "illuminationPercent"),
                doubleValue(node, "phaseAngleDegrees"),
                nullableDouble(node, "brightLimbTiltDegrees"),
                nullableDouble(node, "northPoleTiltDegrees"),
                text(node, "phaseName")
        );
    }

    private static OpportunitySearchResponse.MoonPath moonPath(JsonNode node) {
        return new OpportunitySearchResponse.MoonPath(
                moonPathPoint(node.path("start")),
                moonPathPoint(node.path("suggested")),
                moonPathPoint(node.path("end")),
                moonPathSamples(node.path("samples"))
        );
    }

    private static OpportunitySearchResponse.MoonPathPoint moonPathPoint(JsonNode node) {
        return new OpportunitySearchResponse.MoonPathPoint(
                text(node, "at"),
                doubleValue(node, "altitudeDegrees"),
                doubleValue(node, "azimuthDegrees"),
                doubleValue(node, "moonPhaseAngleDegrees"),
                nullableDouble(node, "brightLimbTiltDegrees"),
                nullableDouble(node, "northPoleTiltDegrees"),
                doubleValue(node, "sunAltitudeDegrees"),
                doubleValue(node, "sunAzimuthDegrees"),
                text(node, "lightBucket"),
                text(node, "role")
        );
    }

    private static ArrayList<OpportunitySearchResponse.MoonPathPoint> moonPathSamples(JsonNode nodes) {
        ArrayList<OpportunitySearchResponse.MoonPathPoint> samples = new ArrayList<>();
        for (JsonNode node : nodes) {
            samples.add(moonPathPoint(node));
        }
        return samples;
    }

    private static OpportunitySearchResponse.Sun sun(JsonNode node) {
        return new OpportunitySearchResponse.Sun(
                doubleValue(node, "altitudeDegrees"),
                doubleValue(node, "azimuthDegrees"),
                text(node, "lightBucket")
        );
    }

    private static OpportunitySearchResponse.Weather weather(JsonNode node) {
        return new OpportunitySearchResponse.Weather(
                text(node, "sourceResolution"),
                text(node, "segmentKind"),
                intValue(node, "cloudCoverMeanPercent"),
                intValue(node, "cloudCoverMaxPercent"),
                intValue(node, "lowCloudCoverMaxPercent"),
                intValue(node, "midCloudCoverMaxPercent"),
                intValue(node, "highCloudCoverMaxPercent"),
                intValue(node, "precipitationProbabilityMaxPercent"),
                doubleValue(node, "precipitationMm"),
                intValue(node, "visibilityMinMeters"),
                intValue(node, "weatherCode"),
                text(node, "summary")
        );
    }

    private static OpportunitySearchResponse.ExposureBalance exposureBalance(JsonNode node) {
        return new OpportunitySearchResponse.ExposureBalance(
                text(node, "label"),
                text(node, "text")
        );
    }

    private static ArrayList<OpportunitySearchResponse.RejectedWindow> rejected(JsonNode nodes) {
        ArrayList<OpportunitySearchResponse.RejectedWindow> rejected = new ArrayList<>();
        for (JsonNode node : nodes) {
            rejected.add(new OpportunitySearchResponse.RejectedWindow(
                    text(node, "startsAt"),
                    text(node, "endsAt"),
                    text(node, "reasonCode"),
                    text(node, "reason"),
                    doubleValue(node, "moonSunSeparationDegrees"),
                    doubleValue(node, "moonIlluminationPercent"),
                    doubleValue(node, "moonAltitudeDegrees"),
                    doubleValue(node, "sunAltitudeDegrees")
            ));
        }
        return rejected;
    }

    private static ArrayList<OpportunitySearchResponse.Message> messages(JsonNode nodes) {
        ArrayList<OpportunitySearchResponse.Message> messages = new ArrayList<>();
        for (JsonNode node : nodes) {
            if ("fixture_weather".equals(text(node, "code"))) {
                continue;
            }
            messages.add(new OpportunitySearchResponse.Message(
                    text(node, "level"),
                    text(node, "code"),
                    text(node, "text")
            ));
        }
        return messages;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asString();
    }

    private static int intValue(JsonNode node, String field) {
        return node.path(field).asInt();
    }

    private static Integer nullableInteger(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private static double doubleValue(JsonNode node, String field) {
        return node.path(field).asDouble();
    }

    private static Double nullableDouble(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }
}
