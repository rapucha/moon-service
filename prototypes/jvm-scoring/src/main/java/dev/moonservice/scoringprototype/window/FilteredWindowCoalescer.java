package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.scoring.ScoringModel;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class FilteredWindowCoalescer {
    private static final Duration MAX_PREFERENCE_GAP = Duration.ofMinutes(10);
    private static final Comparator<MoonWindow> BEST_SUGGESTION = Comparator
            .comparingInt((MoonWindow window) -> ScoringModel.candidateFit(window.suggested()))
            .thenComparing(window -> window.suggested().instant(), Comparator.reverseOrder());

    private FilteredWindowCoalescer() {
    }

    record SourceWindow(MoonWindow source, MoonWindow retained) {
        SourceWindow {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(retained, "retained");
            if (!source.passId().equals(retained.passId())
                    || retained.startsAt().isBefore(source.startsAt())
                    || retained.endsAt().isAfter(source.endsAt())) {
                throw new IllegalArgumentException("retained window must be inside its source window");
            }
        }
    }

    static List<MoonWindow> coalesce(List<SourceWindow> sourceWindows) {
        Objects.requireNonNull(sourceWindows, "sourceWindows");
        Map<String, List<SourceWindow>> byPassId = new LinkedHashMap<>();
        sourceWindows.forEach(sourceWindow -> byPassId
                .computeIfAbsent(sourceWindow.retained().passId(), ignored -> new ArrayList<>())
                .add(sourceWindow));

        List<MoonWindow> result = new ArrayList<>();
        byPassId.values().forEach(pass -> coalescePass(pass, result));
        return result.stream()
                .sorted(Comparator.comparing(MoonWindow::startsAt).thenComparing(MoonWindow::passId))
                .toList();
    }

    private static void coalescePass(List<SourceWindow> members, List<MoonWindow> result) {
        members.sort(Comparator.comparing((SourceWindow member) -> member.retained().startsAt())
                .thenComparing(member -> member.retained().endsAt()));
        List<SourceWindow> group = new ArrayList<>();
        for (SourceWindow member : members) {
            if (!group.isEmpty() && !joins(group.getLast(), member)) {
                result.add(combined(group));
                group.clear();
            }
            group.add(member);
        }
        if (!group.isEmpty()) {
            result.add(combined(group));
        }
    }

    private static boolean joins(SourceWindow previous, SourceWindow next) {
        return !next.retained().startsAt().isAfter(
                previous.retained().endsAt().plus(MAX_PREFERENCE_GAP))
                && !next.source().startsAt().isAfter(previous.source().endsAt());
    }

    private static MoonWindow combined(List<SourceWindow> group) {
        if (group.size() == 1) {
            return group.getFirst().retained();
        }
        MoonWindow first = group.getFirst().retained();
        MoonWindow last = group.stream().map(SourceWindow::retained)
                .max(Comparator.comparing(MoonWindow::endsAt)).orElseThrow();
        List<MoonWindow> candidates = group.stream().map(SourceWindow::retained)
                .filter(window -> window.suggested().instant().isBefore(last.endsAt()))
                .toList();
        List<MoonWindow> eligible = candidates.stream()
                .filter(window -> ScoringModel.ordinaryVisibilityRejectionReason(window).isEmpty())
                .toList();
        MoonWindow selected = (eligible.isEmpty() ? candidates : eligible).stream()
                .max(BEST_SUGGESTION).orElseThrow();

        Map<Instant, MoonSample> path = new TreeMap<>();
        group.stream().map(SourceWindow::retained).flatMap(window -> window.pathSamples().stream())
                .forEach(sample -> path.put(sample.instant(), sample));
        path.put(first.startsAt(), first.start());
        path.put(selected.suggested().instant(), selected.suggested());
        path.put(last.endsAt(), last.end());
        return new MoonWindow(
                first.location(), selected.kind(), first.passStartsAt(), first.passEndsAt(),
                first.startsAt(), first.start(), selected.suggested(), last.end(), last.endsAt(),
                first.passPathSamples(), List.copyOf(path.values()));
    }
}
