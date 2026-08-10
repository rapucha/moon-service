package dev.moonservice.scoringprototype.service;

import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.scoring.ComponentScores;
import dev.moonservice.scoringprototype.scoring.RejectedWindow;
import dev.moonservice.scoringprototype.scoring.ScoredWindow;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import dev.moonservice.scoringprototype.scoring.WeatherRanking;
import dev.moonservice.scoringprototype.window.MoonWindow;
import dev.moonservice.scoringprototype.window.OpportunityHardFilter;
import dev.moonservice.scoringprototype.window.WindowGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class OpportunityService {
    private final EphemerisSampler sampler;
    private final WindowGenerator windowGenerator;
    private final OpportunityHardFilter hardFilter;
    private final WindowWeatherProvider weatherProvider;

    @FunctionalInterface
    public interface WindowAdjustment {
        Optional<MoonWindow> adjust(MoonWindow window, WindowGenerator.SampleProvider samples);
    }

    public enum ResultOrder {
        BEST_MATCH,
        SOONEST
    }

    public OpportunityService() {
        this(
                new EphemerisSampler(),
                new WindowGenerator(),
                WindowWeatherProvider.sameWeatherForEveryWindow(WeatherFixture.PRAGUE_PARTLY_CLOUDY));
    }

    OpportunityService(EphemerisSampler sampler, WindowGenerator windowGenerator, WeatherFixture weather) {
        this(sampler, windowGenerator, WindowWeatherProvider.sameWeatherForEveryWindow(weather));
    }

    OpportunityService(EphemerisSampler sampler, WindowGenerator windowGenerator, WindowWeatherProvider weatherProvider) {
        this.sampler = sampler;
        this.windowGenerator = windowGenerator;
        this.hardFilter = new OpportunityHardFilter();
        this.weatherProvider = weatherProvider;
    }

    public record PreferenceEvaluation(
            PrototypeResult result,
            int appliedPreferenceVersion,
            Map<String, Object> normalizedActiveFilters,
            int excludedSampleCount,
            boolean preferencesRemovedAllLiveCandidates,
            Map<String, List<OpportunityHardFilter.MatchInterval>> azimuthMatchIntervals
    ) {
        public PreferenceEvaluation {
            normalizedActiveFilters = Map.copyOf(normalizedActiveFilters);
            Map<String, List<OpportunityHardFilter.MatchInterval>> masks = new LinkedHashMap<>();
            azimuthMatchIntervals.forEach((passId, intervals) -> masks.put(passId, List.copyOf(intervals)));
            azimuthMatchIntervals = Map.copyOf(masks);
        }
    }

    public PrototypeResult evaluate(PrototypeConfig config) {
        return evaluate(config, weatherProvider);
    }

    public PrototypeResult evaluate(PrototypeConfig config, WindowWeatherProvider weatherProvider) {
        return evaluate(config, weatherProvider, (window, samples) -> Optional.of(window));
    }

    public PrototypeResult evaluate(
            PrototypeConfig config,
            WindowWeatherProvider weatherProvider,
            WindowAdjustment windowAdjustment
    ) {
        return evaluate(config, weatherProvider, windowAdjustment, ResultOrder.BEST_MATCH);
    }

    public PrototypeResult evaluate(
            PrototypeConfig config,
            WindowWeatherProvider weatherProvider,
            WindowAdjustment windowAdjustment,
            ResultOrder order
    ) {
        return evaluate(config, weatherProvider, windowAdjustment, order, WeatherRanking.BALANCED);
    }

    public PrototypeResult evaluate(
            PrototypeConfig config,
            WindowWeatherProvider weatherProvider,
            WindowAdjustment windowAdjustment,
            ResultOrder order,
            WeatherRanking weatherRanking
    ) {
        Objects.requireNonNull(weatherRanking, "weatherRanking");
        WindowGenerator.SampleProvider samples = instant -> sampler.sampleAt(config.location(), instant);
        List<MoonWindow> windows = windowGenerator.findWindows(config, samples);
        List<ScoredWindow> scored = new ArrayList<>();
        List<RejectedWindow> rejected = new ArrayList<>();

        for (MoonWindow window : windows) {
            Optional<MoonWindow> adjusted = windowAdjustment.adjust(window, samples);
            if (adjusted.isPresent()) {
                MoonWindow adjustedWindow = adjusted.get();
                Optional<String> visibilityRejection = ScoringModel.ordinaryVisibilityRejectionReason(adjustedWindow);
                if (visibilityRejection.isPresent()) {
                    rejected.add(RejectedWindow.visibility(adjustedWindow, visibilityRejection.get()));
                    continue;
                }
                WeatherFixture weather = weatherProvider.weatherFor(adjustedWindow);
                ComponentScores components = ScoringModel.scoreWindow(adjustedWindow, weather, weatherRanking);
                scored.add(new ScoredWindow(adjustedWindow, weather, components));
            }
        }

        scored.sort(comparator(order));
        if (scored.size() > config.limit()) {
            scored = scored.subList(0, config.limit());
        }

        return new PrototypeResult(config, windows.size(), scored, rejected);
    }

    public PreferenceEvaluation evaluate(
            PrototypeConfig config,
            WindowWeatherProvider weatherProvider,
            OpportunityPreferences preferences,
            Instant notBefore
    ) {
        return evaluate(config, weatherProvider, preferences, notBefore, ResultOrder.BEST_MATCH);
    }

    public PreferenceEvaluation evaluate(
            PrototypeConfig config,
            WindowWeatherProvider weatherProvider,
            OpportunityPreferences preferences,
            Instant notBefore,
            ResultOrder order
    ) {
        return evaluate(
                config, weatherProvider, preferences, notBefore, order, WeatherRanking.BALANCED);
    }

    public PreferenceEvaluation evaluate(
            PrototypeConfig config,
            WindowWeatherProvider weatherProvider,
            OpportunityPreferences preferences,
            Instant notBefore,
            ResultOrder order,
            WeatherRanking weatherRanking
    ) {
        Objects.requireNonNull(weatherRanking, "weatherRanking");
        if (!preferences.active()) {
            PrototypeResult result = evaluate(
                    config,
                    weatherProvider,
                    (window, samples) -> window.startsAt().isAfter(notBefore)
                            ? Optional.of(window)
                            : WindowGenerator.withSuggestedAtOrAfter(window, samples, notBefore),
                    order,
                    weatherRanking);
            return new PreferenceEvaluation(result, preferences.version(), Map.of(), 0, false, Map.of());
        }

        WindowGenerator.SampleProvider samples = instant -> sampler.sampleAt(config.location(), instant);
        List<MoonWindow> completeWindows = windowGenerator.findWindows(config, samples);
        boolean hasLiveCandidate =
                completeWindows.stream().anyMatch(window -> window.endsAt().isAfter(notBefore));
        OpportunityHardFilter.Result filtered = hardFilter.filter(
                config.location(),
                completeWindows,
                samples,
                instant -> sampler.topocentricLunarAngularRadiusDegrees(config.location(), instant),
                preferences,
                notBefore);
        PrototypeResult result = score(
                config,
                weatherProvider,
                completeWindows.size(),
                filtered.windows(),
                order,
                weatherRanking);
        Set<String> returnedPassIds = result.opportunities().stream()
                .map(item -> item.window().passId())
                .collect(Collectors.toSet());
        Map<String, List<OpportunityHardFilter.MatchInterval>> returnedMasks = new LinkedHashMap<>();
        filtered.azimuthMatchIntervals().forEach((passId, intervals) -> {
            if (returnedPassIds.contains(passId)) {
                returnedMasks.put(passId, intervals);
            }
        });
        return new PreferenceEvaluation(
                result,
                preferences.version(),
                preferences.normalizedFilters(),
                filtered.excludedSampleCount(),
                hasLiveCandidate && filtered.windows().isEmpty(),
                returnedMasks);
    }

    private static PrototypeResult score(
            PrototypeConfig config,
            WindowWeatherProvider weatherProvider,
            int candidateWindowsEvaluated,
            List<MoonWindow> windows,
            ResultOrder order,
            WeatherRanking weatherRanking
    ) {
        List<ScoredWindow> scored = new ArrayList<>();
        List<RejectedWindow> rejected = new ArrayList<>();
        for (MoonWindow window : windows) {
            Optional<String> visibilityRejection = ScoringModel.ordinaryVisibilityRejectionReason(window);
            if (visibilityRejection.isPresent()) {
                rejected.add(RejectedWindow.visibility(window, visibilityRejection.get()));
                continue;
            }
            WeatherFixture weather = weatherProvider.weatherFor(window);
            scored.add(new ScoredWindow(
                    window,
                    weather,
                    ScoringModel.scoreWindow(window, weather, weatherRanking)));
        }
        scored.sort(comparator(order));
        if (scored.size() > config.limit()) {
            scored = scored.subList(0, config.limit());
        }
        return new PrototypeResult(config, candidateWindowsEvaluated, scored, rejected);
    }

    private static Comparator<ScoredWindow> comparator(ResultOrder order) {
        // A missing value must not fall through to Soonest; callers choose a closed order.
        if (Objects.requireNonNull(order, "order") == ResultOrder.BEST_MATCH) {
            // Preserve the existing ranking: highest score first, then the earlier suggestion.
            return Comparator.comparingInt((ScoredWindow item) -> item.components().total()).reversed()
                    .thenComparing(item -> item.window().suggested().instant());
        }
        // Soonest starts with actionable time, then prefers the higher score for equal times.
        // The stable opportunity ID is the final deterministic key. If every key matches,
        // the comparator returns equality so the stable list sort retains source order.
        return Comparator.comparing((ScoredWindow item) -> item.window().suggested().instant())
                .thenComparing(Comparator.comparingInt(
                        (ScoredWindow item) -> item.components().total()).reversed())
                .thenComparing(item -> item.window().id());
    }
}
