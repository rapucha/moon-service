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
import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.output.ResponseFormatter;
import dev.moonservice.scoringprototype.service.OpportunityService;
import dev.moonservice.scoringprototype.service.PrototypeResult;
import dev.moonservice.scoringprototype.window.OpportunityHardFilter;
import dev.moonservice.scoringprototype.window.PreferenceImpactAnalysis;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ScoringOpportunitySearchEngine implements OpportunitySearchEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final EphemerisSampler EPHEMERIS = new EphemerisSampler();
    private static final PreferenceImpactAnalysis PREFERENCE_IMPACT =
            new PreferenceImpactAnalysis();

    private final PreviewEvaluator previewEvaluator;
    private final OpportunityService opportunityService;
    private final ResponseFormatter responseFormatter;
    private final WeatherForecastProvider weatherForecastProvider;

    public ScoringOpportunitySearchEngine(
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
            return PrototypeOpportunityResponseMapper.map(
                    previewEvaluator.evaluateJson(prototypeRequest));
        } catch (UsageException ex) {
            throw new InvalidOpportunitySearchRequestException(ex.getMessage(), ex);
        }
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

    @Override
    public PreferenceSearchResult search(
            ResolvedLocation location,
            OpportunitySearchRequest request,
            Instant notBefore,
            OpportunityPreferences preferences
    ) {
        Objects.requireNonNull(notBefore, "notBefore");
        Objects.requireNonNull(preferences, "preferences");
        try {
            PrototypeConfig config = toPrototypeConfig(location, request);
            WeatherForecast forecast = weatherForecastProvider.forecastFor(
                    location,
                    config.start(),
                    config.end());
            OpportunityService.PreferenceEvaluation evaluation = switch (request.order()) {
                case BEST_MATCH -> opportunityService.evaluate(
                        config,
                        window -> forecast.weatherAt(window.suggested().instant()).toWeatherFixture(),
                        preferences,
                        notBefore,
                        OpportunityService.ResultOrder.BEST_MATCH,
                        request.weatherRanking());
                case SOONEST -> opportunityService.evaluate(
                        config,
                        window -> forecast.weatherAt(window.suggested().instant()).toWeatherFixture(),
                        preferences,
                        notBefore,
                        OpportunityService.ResultOrder.SOONEST,
                        request.weatherRanking());
            };
            OpportunitySearchResponse response =
                    PrototypeOpportunityResponseMapper.map(responseFormatter.format(evaluation.result()));
            return new PreferenceSearchResult(
                    response,
                    evaluation.appliedPreferenceVersion(),
                    evaluation.normalizedActiveFilters(),
                    evaluation.excludedSampleCount(),
                    evaluation.preferencesRemovedAllLiveCandidates(),
                    toBackendAzimuthMatchIntervals(evaluation.azimuthMatchIntervals()),
                    preferenceImpact(config, notBefore, preferences));
        } catch (UsageException ex) {
            throw new IllegalStateException("Resolved opportunity scoring request was invalid.", ex);
        }
    }

    private OpportunitySearchResponse searchResolvedLocation(
            ResolvedLocation location,
            OpportunitySearchRequest request,
            OpportunityService.WindowAdjustment windowAdjustment
    ) {
        try {
            PrototypeConfig config = toPrototypeConfig(location, request);
            WeatherForecast forecast = weatherForecastProvider.forecastFor(
                    location,
                    config.start(),
                    config.end());
            PrototypeResult result = opportunityService.evaluate(
                    config,
                    window -> forecast.weatherAt(window.suggested().instant()).toWeatherFixture(),
                    windowAdjustment,
                    toResultOrder(request.order()),
                    request.weatherRanking());
            return PrototypeOpportunityResponseMapper.map(responseFormatter.format(result));
        } catch (UsageException ex) {
            throw new IllegalStateException("Resolved opportunity scoring request was invalid.", ex);
        }
    }

    private static PrototypeConfig toPrototypeConfig(
            ResolvedLocation location,
            OpportunitySearchRequest request
    ) {
        return new PrototypeConfig(
                toPrototypeLocation(location),
                request.startDate(),
                request.forecastHorizonDays(),
                request.maxMoonAltitudeDegrees(),
                request.limit());
    }

    private static OpportunityService.ResultOrder toResultOrder(
            OpportunitySearchRequest.Order order
    ) {
        return switch (order) {
            case BEST_MATCH -> OpportunityService.ResultOrder.BEST_MATCH;
            case SOONEST -> OpportunityService.ResultOrder.SOONEST;
        };
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

    private static PreferenceImpactAnalysis.Result preferenceImpact(
            PrototypeConfig config,
            Instant notBefore,
            OpportunityPreferences preferences
    ) {
        return preferences.active() ? PREFERENCE_IMPACT.analyze(
                config,
                notBefore,
                preferences,
                instant -> EPHEMERIS.sampleAt(config.location(), instant),
                instant -> EPHEMERIS.topocentricLunarAngularRadiusDegrees(
                        config.location(), instant)) : null;
    }

    private static Map<String, List<AzimuthMatchInterval>> toBackendAzimuthMatchIntervals(
            Map<String, List<OpportunityHardFilter.MatchInterval>> intervalsByPassId
    ) {
        Map<String, List<AzimuthMatchInterval>> result = new LinkedHashMap<>();
        intervalsByPassId.forEach((passId, intervals) -> result.put(
                passId,
                intervals.stream()
                        .map(interval -> new AzimuthMatchInterval(interval.startsAt(), interval.endsAt()))
                        .toList()));
        return result;
    }

}
