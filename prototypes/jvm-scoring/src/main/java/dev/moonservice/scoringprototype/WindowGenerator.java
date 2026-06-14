package dev.moonservice.scoringprototype;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class WindowGenerator {
    List<MoonWindow> findWindows(List<MoonSample> samples, PrototypeConfig config) {
        List<MoonWindow> windows = new ArrayList<>();
        List<MoonSample> current = new ArrayList<>();

        for (MoonSample sample : samples) {
            if (sample.moonAltitudeDegrees() >= 0.0
                    && sample.moonAltitudeDegrees() <= config.maxMoonAltitudeDegrees()) {
                current.add(sample);
            } else {
                flushWindow(windows, current, config);
            }
        }
        flushWindow(windows, current, config);
        return windows;
    }

    private static void flushWindow(List<MoonWindow> windows, List<MoonSample> samples, PrototypeConfig config) {
        if (samples.isEmpty()) {
            return;
        }
        MoonSample peak = samples.stream()
                .max(Comparator.comparingInt(ScoringModel::candidateFit))
                .orElseThrow();
        Duration halfStep = Duration.ofMinutes(config.stepMinutes() / 2L);
        Instant startsAt = max(config.start(), samples.getFirst().instant().minus(halfStep));
        Instant endsAt = min(config.end(), samples.getLast().instant().plus(halfStep));
        windows.add(new MoonWindow(config.location(), startsAt, peak, endsAt, samples.size()));
        samples.clear();
    }

    private static Instant max(Instant a, Instant b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
