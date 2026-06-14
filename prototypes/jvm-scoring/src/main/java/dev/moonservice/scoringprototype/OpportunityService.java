package dev.moonservice.scoringprototype;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpportunityService {
    private final EphemerisSampler sampler;
    private final WindowGenerator windowGenerator;
    private final WeatherFixture weather;

    OpportunityService() {
        this(new EphemerisSampler(), new WindowGenerator(), WeatherFixture.PRAGUE_PARTLY_CLOUDY);
    }

    OpportunityService(EphemerisSampler sampler, WindowGenerator windowGenerator, WeatherFixture weather) {
        this.sampler = sampler;
        this.windowGenerator = windowGenerator;
        this.weather = weather;
    }

    PrototypeResult evaluate(PrototypeConfig config) {
        List<MoonSample> samples = sampler.sample(config);
        List<MoonWindow> windows = windowGenerator.findWindows(samples, config);
        List<ScoredWindow> scored = new ArrayList<>();
        List<RejectedWindow> rejected = new ArrayList<>();
        Map<String, Integer> rejectedCounts = new LinkedHashMap<>();

        for (MoonWindow window : windows) {
            List<String> rejectionReasons = ScoringModel.hardFilterReasons(window, weather, config);
            if (!rejectionReasons.isEmpty()) {
                countRejections(rejectedCounts, rejectionReasons);
                rejected.add(new RejectedWindow(window.id(), null, rejectionReasons));
                continue;
            }

            ComponentScores components = ScoringModel.scoreWindow(window, weather);
            if (components.total() < config.minScore()) {
                countRejections(rejectedCounts, List.of("below_minimum_score"));
                rejected.add(new RejectedWindow(window.id(), components.total(), List.of("below_minimum_score")));
                continue;
            }

            scored.add(new ScoredWindow(window, weather, components));
        }

        scored.sort(Comparator.comparingInt((ScoredWindow item) -> item.components().total()).reversed()
                .thenComparing(item -> item.window().peak().instant()));
        if (scored.size() > config.limit()) {
            scored = scored.subList(0, config.limit());
        }

        return new PrototypeResult(config, samples, scored, rejected, rejectedCounts);
    }

    private static void countRejections(Map<String, Integer> rejectedCounts, List<String> reasons) {
        for (String reason : reasons) {
            rejectedCounts.put(reason, rejectedCounts.getOrDefault(reason, 0) + 1);
        }
    }
}
