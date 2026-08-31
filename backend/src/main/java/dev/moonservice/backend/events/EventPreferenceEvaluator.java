package dev.moonservice.backend.events;

import dev.moonservice.backend.events.MoonEventResponse.FilterAssessment;
import dev.moonservice.backend.events.MoonEventResponse.PreferenceAssessment;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import dev.moonservice.scoringprototype.window.Version1PreferenceMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class EventPreferenceEvaluator {
    static PreferenceAssessment evaluate(
            OpportunityPreferences preferences,
            MoonSample sample,
            double lunarRadiusDegrees
    ) {
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(sample, "sample");
        List<FilterAssessment> filters = new ArrayList<>();
        if (preferences.altitudeDegrees() != null) {
            filters.add(assessment(
                    "altitudeDegrees",
                    Version1PreferenceMatcher.matchesAltitude(
                            sample, preferences.altitudeDegrees())));
        }
        if (preferences.azimuthDegrees() != null) {
            filters.add(assessment(
                    "azimuthDegrees",
                    Version1PreferenceMatcher.matchesAzimuth(
                            sample, lunarRadiusDegrees, preferences.azimuthDegrees())));
        }
        String overall = filters.isEmpty() ? "no_active_preferences"
                : filters.stream().allMatch(filter -> filter.status().equals("matches"))
                ? "matches" : "does_not_match";
        return new PreferenceAssessment(overall, filters);
    }

    private static FilterAssessment assessment(String filter, boolean matches) {
        return new FilterAssessment(filter, matches ? "matches" : "does_not_match");
    }
}
