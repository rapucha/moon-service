package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.scoring.ScoringModel;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public final class PreferenceImpactAnalysis {
    static final int LOOK_AHEAD_DAYS = 200;
    private static final Duration LOOK_AHEAD = Duration.ofDays(LOOK_AHEAD_DAYS);
    private static final Duration SAMPLE_STEP = Duration.ofMinutes(5);
    private static final Duration REFINEMENT_TOLERANCE = Duration.ofSeconds(1);

    private final WindowGenerator windowGenerator = new WindowGenerator();
    private final OpportunityHardFilter hardFilter = new OpportunityHardFilter();

    public Result analyze(
            PrototypeConfig config,
            Instant notBefore,
            OpportunityPreferences preferences,
            WindowGenerator.SampleProvider sampleProvider,
            OpportunityHardFilter.LunarRadiusProvider radiusProvider
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(notBefore, "notBefore");
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(sampleProvider, "sampleProvider");
        Objects.requireNonNull(radiusProvider, "radiusProvider");
        if (!preferences.active()) {
            throw new IllegalArgumentException("preferences must contain an active filter.");
        }

        Map<Instant, MoonSample> sampleCache = new HashMap<>();
        Map<Instant, Double> radiusCache = new HashMap<>();
        WindowGenerator.SampleProvider samples =
                instant -> sampleCache.computeIfAbsent(instant, sampleProvider::sampleAt);
        OpportunityHardFilter.LunarRadiusProvider radii =
                instant -> radiusCache.computeIfAbsent(instant, radiusProvider::angularRadiusDegrees);
        List<MoonWindow> baseline = baselineWindows(
                windowGenerator.findWindows(config, samples), samples, notBefore);
        List<ActiveFilter> activeFilters = activeFilters(preferences);
        List<MutableImpact> impacts = countMatches(
                config.location(), baseline, activeFilters, samples, radii, notBefore);
        findNextMatches(config.location(), activeFilters, impacts, samples, radii, notBefore);
        return new Result(
                baseline.size(),
                impacts.stream().map(MutableImpact::toResult).toList());
    }

    private static List<MoonWindow> baselineWindows(
            List<MoonWindow> completeWindows,
            WindowGenerator.SampleProvider samples,
            Instant notBefore
    ) {
        return completeWindows.stream()
                .map(window -> WindowGenerator.withSuggestedAtOrAfter(window, samples, notBefore))
                .flatMap(java.util.Optional::stream)
                .filter(window -> ScoringModel.ordinaryVisibilityRejectionReason(window).isEmpty())
                .toList();
    }

    private List<MutableImpact> countMatches(
            Location location,
            List<MoonWindow> baseline,
            List<ActiveFilter> activeFilters,
            WindowGenerator.SampleProvider samples,
            OpportunityHardFilter.LunarRadiusProvider radii,
            Instant notBefore
    ) {
        List<MutableImpact> impacts = new ArrayList<>();
        for (ActiveFilter filter : activeFilters) {
            int matching = 0;
            for (MoonWindow source : baseline) {
                List<MoonWindow> retained = hardFilter.filter(
                        location, List.of(source), samples, radii, filter.preferences(), notBefore).windows();
                if (retained.stream()
                        .anyMatch(window -> ScoringModel.ordinaryVisibilityRejectionReason(window).isEmpty())) {
                    matching++;
                }
            }
            impacts.add(new MutableImpact(filter.key(), matching));
        }
        return impacts;
    }

    private static void findNextMatches(
            Location location,
            List<ActiveFilter> filters,
            List<MutableImpact> impacts,
            WindowGenerator.SampleProvider samples,
            OpportunityHardFilter.LunarRadiusProvider radii,
            Instant notBefore
    ) {
        Instant previous = notBefore;
        MoonSample initial = samples.sampleAt(previous);
        for (int index = 0; index < filters.size(); index++) {
            if (matches(location, initial, radii, filters.get(index).preferences())) {
                impacts.get(index).nextMatchAt = previous;
            }
        }

        Instant endsAt = notBefore.plus(LOOK_AHEAD);
        while (previous.isBefore(endsAt) && impacts.stream().anyMatch(MutableImpact::unresolved)) {
            Instant next = min(previous.plus(SAMPLE_STEP), endsAt);
            MoonSample sample = samples.sampleAt(next);
            for (int index = 0; index < filters.size(); index++) {
                MutableImpact impact = impacts.get(index);
                OpportunityPreferences singleton = filters.get(index).preferences();
                if (impact.unresolved() && matches(location, sample, radii, singleton)) {
                    Predicate<Instant> predicate =
                            instant -> matches(location, samples.sampleAt(instant), radii, singleton);
                    impact.nextMatchAt = refineFalseToTrue(previous, next, predicate);
                }
            }
            previous = next;
        }
    }

    private static boolean matches(
            Location location,
            MoonSample sample,
            OpportunityHardFilter.LunarRadiusProvider radii,
            OpportunityPreferences preferences
    ) {
        return sample.moonAltitudeDegrees() >= 0.0
                && OpportunityHardFilter.matchesAll(location, sample, radii, preferences);
    }

    private static Instant refineFalseToTrue(
            Instant start,
            Instant end,
            Predicate<Instant> matches
    ) {
        Instant lower = start;
        Instant upper = end;
        while (Duration.between(lower, upper).compareTo(REFINEMENT_TOLERANCE) > 0) {
            Instant middle = lower.plus(Duration.between(lower, upper).dividedBy(2));
            if (matches.test(middle)) {
                upper = middle;
            } else {
                lower = middle;
            }
        }
        return upper;
    }

    private static List<ActiveFilter> activeFilters(OpportunityPreferences preferences) {
        List<ActiveFilter> filters = new ArrayList<>();
        if (preferences.altitudeDegrees() != null) {
            filters.add(new ActiveFilter("altitudeDegrees", new OpportunityPreferences(
                    OpportunityPreferences.VERSION, preferences.altitudeDegrees(), null, null, null, null)));
        }
        if (preferences.azimuthDegrees() != null) {
            filters.add(new ActiveFilter("azimuthDegrees", new OpportunityPreferences(
                    OpportunityPreferences.VERSION, null, preferences.azimuthDegrees(), null, null, null)));
        }
        if (preferences.time() != null) {
            filters.add(new ActiveFilter("time", new OpportunityPreferences(
                    OpportunityPreferences.VERSION, null, null, preferences.time(), null, null)));
        }
        if (preferences.namedPhases() != null) {
            filters.add(new ActiveFilter("namedPhases", new OpportunityPreferences(
                    OpportunityPreferences.VERSION, null, null, null, preferences.namedPhases(), null)));
        }
        if (preferences.brightLimbOrientationDegrees() != null) {
            filters.add(new ActiveFilter("brightLimbOrientationDegrees", new OpportunityPreferences(
                    OpportunityPreferences.VERSION, null, null, null, null,
                    preferences.brightLimbOrientationDegrees())));
        }
        return List.copyOf(filters);
    }

    private static Instant min(Instant left, Instant right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    public record Result(int unfilteredOpportunityCount, List<FilterImpact> filters) {
        public Result {
            if (unfilteredOpportunityCount < 0) {
                throw new IllegalArgumentException("unfilteredOpportunityCount must not be negative.");
            }
            filters = List.copyOf(filters);
        }
    }

    public record FilterImpact(
            String filter,
            int matchingOpportunityCount,
            int lookAheadDays,
            Instant nextMatchAt
    ) {
        public FilterImpact {
            Objects.requireNonNull(filter, "filter");
            if (matchingOpportunityCount < 0) {
                throw new IllegalArgumentException("matchingOpportunityCount must not be negative.");
            }
            if (lookAheadDays != LOOK_AHEAD_DAYS) {
                throw new IllegalArgumentException("lookAheadDays must be " + LOOK_AHEAD_DAYS + ".");
            }
        }
    }

    private record ActiveFilter(String key, OpportunityPreferences preferences) {
    }

    private static final class MutableImpact {
        private final String key;
        private final int matching;
        private Instant nextMatchAt;

        private MutableImpact(String key, int matching) {
            this.key = key;
            this.matching = matching;
        }

        private boolean unresolved() {
            return nextMatchAt == null;
        }

        private FilterImpact toResult() {
            return new FilterImpact(key, matching, LOOK_AHEAD_DAYS, nextMatchAt);
        }
    }
}
