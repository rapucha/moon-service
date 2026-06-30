package dev.moonservice.backend.opportunity.scoring;

import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.backend.weather.WeatherForecast;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import dev.moonservice.scoringprototype.PreviewEvaluator;
import dev.moonservice.scoringprototype.UsageException;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.output.ResponseFormatter;
import dev.moonservice.scoringprototype.service.OpportunityService;
import dev.moonservice.scoringprototype.service.PrototypeResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class JvmScoringOpportunitySearchEngine implements OpportunitySearchEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PreviewEvaluator previewEvaluator;
    private final OpportunityService opportunityService;
    private final ResponseFormatter responseFormatter;
    private final WeatherForecastProvider weatherForecastProvider;

    public JvmScoringOpportunitySearchEngine(
            PreviewEvaluator previewEvaluator,
            WeatherForecastProvider weatherForecastProvider
    ) {
        this.previewEvaluator = previewEvaluator;
        this.opportunityService = new OpportunityService();
        this.responseFormatter = new ResponseFormatter();
        this.weatherForecastProvider = weatherForecastProvider;
    }

    @Override
    public OpportunitySearchResponse search(OpportunitySearchRequest request) {
        ObjectNode prototypeRequest = MAPPER.createObjectNode();
        prototypeRequest.put("locationId", request.locationId());
        prototypeRequest.put("start", request.startDate().toString());
        prototypeRequest.put("forecastHorizonDays", request.forecastHorizonDays());
        prototypeRequest.put("maxMoonAltitudeDegrees", request.maxMoonAltitudeDegrees());
        prototypeRequest.put("limit", request.limit());
        try {
            return toBackendResponse(previewEvaluator.evaluateJson(prototypeRequest));
        } catch (UsageException ex) {
            throw new InvalidOpportunitySearchRequestException(ex.getMessage(), ex);
        }
    }

    @Override
    public OpportunitySearchResponse search(ResolvedLocation location, OpportunitySearchRequest request) {
        return searchResolvedLocation(location, request, (window, samples) -> Optional.of(window));
    }

    @Override
    public OpportunitySearchResponse search(
            ResolvedLocation location,
            OpportunitySearchRequest request,
            Instant notBefore
    ) {
        return searchResolvedLocation(
                location,
                request,
                new LiveOpportunityWindowSelector(Objects.requireNonNull(notBefore, "notBefore")));
    }

    private OpportunitySearchResponse searchResolvedLocation(
            ResolvedLocation location,
            OpportunitySearchRequest request,
            OpportunityService.WindowAdjustment windowAdjustment
    ) {
        try {
            PrototypeConfig config = new PrototypeConfig(
                    toPrototypeLocation(location),
                    request.startDate(),
                    request.forecastHorizonDays(),
                    request.maxMoonAltitudeDegrees(),
                    request.limit());
            WeatherForecast forecast = weatherForecastProvider.forecastFor(
                    location,
                    config.start(),
                    config.end(),
                    request.forecastHorizonDays());
            PrototypeResult result = opportunityService.evaluate(
                    config,
                    window -> forecast.weatherAt(window.suggested().instant()).toWeatherFixture(),
                    windowAdjustment);
            return toBackendResponse(responseFormatter.format(result));
        } catch (UsageException ex) {
            throw new IllegalStateException("Resolved opportunity scoring request was invalid.", ex);
        }
    }

    private static Location toPrototypeLocation(ResolvedLocation location) {
        return new Location(
                location.locationId(),
                "real_location",
                location.locationId(),
                location.displayName(),
                location.latitude(),
                location.longitude(),
                location.elevationMeters(),
                location.zoneId().getId(),
                location.countryCode());
    }

    private static OpportunitySearchResponse toBackendResponse(String prototypeJson) {
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
                    text(node, "startsAt"),
                    text(node, "suggestedAt"),
                    text(node, "endsAt"),
                    text(node, "localTimeZone"),
                    intValue(node, "score"),
                    text(node, "confidence"),
                    componentScores(node.path("components")),
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

    private static OpportunitySearchResponse.ComponentScores componentScores(JsonNode node) {
        return new OpportunitySearchResponse.ComponentScores(
                intValue(node, "moonAltitudeFit"),
                intValue(node, "sunLightFit"),
                intValue(node, "moonIlluminationFit"),
                intValue(node, "weatherFit"),
                intValue(node, "forecastConfidence")
        );
    }

    private static OpportunitySearchResponse.Moon moon(JsonNode node) {
        return new OpportunitySearchResponse.Moon(
                doubleValue(node, "altitudeDegrees"),
                doubleValue(node, "azimuthDegrees"),
                doubleValue(node, "illuminationPercent"),
                doubleValue(node, "phaseAngleDegrees"),
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
                doubleValue(node, "sunAltitudeDegrees"),
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
                    text(node, "reason")
            ));
        }
        return rejected;
    }

    private static ArrayList<OpportunitySearchResponse.Message> messages(JsonNode nodes) {
        ArrayList<OpportunitySearchResponse.Message> messages = new ArrayList<>();
        for (JsonNode node : nodes) {
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

    private static double doubleValue(JsonNode node, String field) {
        return node.path(field).asDouble();
    }
}
