package dev.moonservice.backend.events;

import dev.moonservice.backend.events.MoonEventResponse.FilterAssessment;
import dev.moonservice.backend.events.MoonEventResponse.PreferenceAssessment;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.NamedPhase;
import dev.moonservice.scoringprototype.window.RefinedTimeGrid;
import dev.moonservice.scoringprototype.window.Version1PreferenceMatcher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

final class EventPreferenceEvaluator {
    record Result(PreferenceAssessment assessment, Instant suggestedAt) {
    }

    record TimeSpan(Instant startsAt, Instant endsAt) {
        TimeSpan {
            Objects.requireNonNull(startsAt, "startsAt");
            Objects.requireNonNull(endsAt, "endsAt");
            if (!endsAt.isAfter(startsAt)) {
                throw new IllegalArgumentException("endsAt must be after startsAt.");
            }
        }
    }

    Result evaluate(
            Location location,
            OpportunityPreferences preferences,
            Instant sampleAnchor,
            Instant maximumAt,
            TimeSpan displayInterval,
            boolean displayEndExclusive,
            List<Instant> extraInstants,
            Function<Instant, MoonSample> samples,
            ToDoubleFunction<Instant> lunarRadii
    ) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(maximumAt, "maximumAt");
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(lunarRadii, "lunarRadii");
        List<FilterRule> rules = rules(location, preferences, samples, lunarRadii);
        List<FilterRule> applicable = rules.stream().filter(FilterRule::applicable).toList();
        Predicate<Instant> commonMatch = instant ->
                applicable.stream().allMatch(rule -> rule.matches().test(instant));

        List<Instant> base = RefinedTimeGrid.sampleInstants(
                sampleAnchor, displayInterval.startsAt(), displayInterval.endsAt(), extraInstants);
        TreeSet<Instant> evaluated = new TreeSet<>(base);
        applicable.forEach(rule -> evaluated.addAll(
                RefinedTimeGrid.transitionInstants(base, rule.matches())));
        List<Instant> augmented = List.copyOf(evaluated);
        if (!applicable.isEmpty()) {
            evaluated.addAll(RefinedTimeGrid.transitionInstants(augmented, commonMatch));
        }
        List<Instant> instants = displayEndExclusive
                ? evaluated.stream().filter(instant -> instant.isBefore(displayInterval.endsAt())).toList()
                : List.copyOf(evaluated);

        List<FilterAssessment> filters = rules.stream().map(rule -> new FilterAssessment(
                rule.name(),
                !rule.applicable() ? "not_applicable"
                        : instants.stream().anyMatch(rule.matches()) ? "matches" : "does_not_match"))
                .toList();
        boolean common = !applicable.isEmpty() && instants.stream().anyMatch(commonMatch);
        String overall = rules.isEmpty() ? "no_active_preferences"
                : applicable.isEmpty() ? "not_applicable"
                : common ? "matches" : "does_not_match";
        List<Instant> suggestions = common
                ? instants.stream().filter(commonMatch).toList()
                : instants;
        Instant suggestedAt = suggestions.stream()
                .min(Comparator.comparing((Instant instant) -> distance(instant, maximumAt))
                        .thenComparing(Instant::compareTo))
                .orElseThrow();
        return new Result(new PreferenceAssessment(overall, filters), suggestedAt);
    }

    private static List<FilterRule> rules(
            Location location,
            OpportunityPreferences preferences,
            Function<Instant, MoonSample> samples,
            ToDoubleFunction<Instant> lunarRadii
    ) {
        List<FilterRule> rules = new ArrayList<>();
        if (preferences.altitudeDegrees() != null) {
            rules.add(new FilterRule("altitudeDegrees", instant ->
                    Version1PreferenceMatcher.matchesAltitude(
                            samples.apply(instant), preferences.altitudeDegrees())));
        }
        if (preferences.azimuthDegrees() != null) {
            rules.add(new FilterRule("azimuthDegrees", instant ->
                    Version1PreferenceMatcher.matchesAzimuth(
                            samples.apply(instant),
                            lunarRadii.applyAsDouble(instant),
                            preferences.azimuthDegrees())));
        }
        if (preferences.time() != null) {
            rules.add(new FilterRule("time", instant ->
                    Version1PreferenceMatcher.matchesTime(
                            location, samples.apply(instant), preferences.time())));
        }
        if (preferences.namedPhases() != null) {
            rules.add(new FilterRule("namedPhases", ignored ->
                    Version1PreferenceMatcher.matchesNamedPhase(
                            NamedPhase.FULL_MOON, preferences.namedPhases())));
        }
        if (preferences.brightLimbOrientationDegrees() != null) {
            rules.add(new FilterRule("brightLimbOrientationDegrees", null));
        }
        return List.copyOf(rules);
    }

    private static Duration distance(Instant left, Instant right) {
        return Duration.between(left, right).abs();
    }

    private record FilterRule(String name, Predicate<Instant> matches) {
        boolean applicable() {
            return matches != null;
        }
    }
}
