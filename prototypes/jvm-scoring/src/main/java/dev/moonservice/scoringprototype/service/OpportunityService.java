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

public final class OpportunityService {
    private final EphemerisSampler sampler;
    private final WindowGenerator windowGenerator;
    private final WindowWeatherProvider weatherProvider;

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
        List<MoonWindow> windows = windowGenerator.findWindows(config, sampler);
        List<ScoredWindow> scored = new ArrayList<>();

        for (MoonWindow window : windows) {
            WeatherFixture weather = weatherProvider.weatherFor(window);
            ComponentScores components = ScoringModel.scoreWindow(window, weather);
            scored.add(new ScoredWindow(window, weather, components));
        }

        scored.sort(Comparator.comparingInt((ScoredWindow item) -> item.components().total()).reversed()
                .thenComparing(item -> item.window().suggested().instant()));
        if (scored.size() > config.limit()) {
            scored = scored.subList(0, config.limit());
        }

        return new PrototypeResult(config, windows.size(), scored);
    }
}
