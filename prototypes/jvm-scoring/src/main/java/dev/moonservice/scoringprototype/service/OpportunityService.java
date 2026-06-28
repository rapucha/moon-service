package dev.moonservice.scoringprototype.service;

import dev.moonservice.scoringprototype.ephemeris.EphemerisSampler;
import dev.moonservice.scoringprototype.fixture.WeatherFixture;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.scoring.ComponentScores;
import dev.moonservice.scoringprototype.scoring.ScoredWindow;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import dev.moonservice.scoringprototype.window.MoonWindow;
import dev.moonservice.scoringprototype.window.WindowGenerator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class OpportunityService {
    private final EphemerisSampler sampler;
    private final WindowGenerator windowGenerator;
    private final WindowWeatherProvider weatherProvider;

    @FunctionalInterface
    public interface WindowAdjustment {
        Optional<MoonWindow> adjust(MoonWindow window, WindowGenerator.SampleProvider samples);
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
        this.weatherProvider = weatherProvider;
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
        WindowGenerator.SampleProvider samples = instant -> sampler.sampleAt(config.location(), instant);
        List<MoonWindow> windows = windowGenerator.findWindows(config, samples);
        List<ScoredWindow> scored = new ArrayList<>();

        for (MoonWindow window : windows) {
            Optional<MoonWindow> adjusted = windowAdjustment.adjust(window, samples);
            if (adjusted.isPresent()) {
                WeatherFixture weather = weatherProvider.weatherFor(adjusted.get());
                ComponentScores components = ScoringModel.scoreWindow(adjusted.get(), weather);
                scored.add(new ScoredWindow(adjusted.get(), weather, components));
            }
        }

        scored.sort(Comparator.comparingInt((ScoredWindow item) -> item.components().total()).reversed()
                .thenComparing(item -> item.window().suggested().instant()));
        if (scored.size() > config.limit()) {
            scored = scored.subList(0, config.limit());
        }

        return new PrototypeResult(config, windows.size(), scored);
    }
}
