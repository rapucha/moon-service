package dev.moonservice.backend.opportunity.search;

import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.scoringprototype.ephemeris.PhaseOrientationAvailability;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface OpportunitySearchEngine {
    OpportunitySearchResponse search(OpportunitySearchRequest request);

    OpportunitySearchResponse search(
            ResolvedLocation location,
            OpportunitySearchRequest request,
            Instant notBefore
    );

    PreferenceSearchResult search(
            ResolvedLocation location,
            OpportunitySearchRequest request,
            Instant notBefore,
            OpportunityPreferences preferences
    );

    record PreferenceSearchResult(
            OpportunitySearchResponse response,
            int appliedPreferenceVersion,
            Map<String, Object> normalizedActiveFilters,
            int excludedSampleCount,
            boolean preferencesRemovedAllLiveCandidates,
            Map<String, List<AzimuthMatchInterval>> azimuthMatchIntervals,
            PhaseOrientationAvailability.Result phaseOrientationAvailability
    ) {
        public PreferenceSearchResult {
            Objects.requireNonNull(response, "response");
            if (appliedPreferenceVersion != OpportunityPreferences.VERSION) {
                throw new IllegalArgumentException(
                        "appliedPreferenceVersion must be " + OpportunityPreferences.VERSION + ".");
            }
            if (excludedSampleCount < 0) {
                throw new IllegalArgumentException("excludedSampleCount must not be negative.");
            }
            normalizedActiveFilters = Map.copyOf(normalizedActiveFilters);
            Map<String, List<AzimuthMatchInterval>> intervals = new LinkedHashMap<>();
            azimuthMatchIntervals.forEach((passId, values) ->
                    intervals.put(passId, List.copyOf(values)));
            azimuthMatchIntervals = Map.copyOf(intervals);
        }
    }

    record AzimuthMatchInterval(Instant startsAt, Instant endsAt) {
        public AzimuthMatchInterval {
            Objects.requireNonNull(startsAt, "startsAt");
            Objects.requireNonNull(endsAt, "endsAt");
            if (!endsAt.isAfter(startsAt)) {
                throw new IllegalArgumentException("endsAt must be after startsAt.");
            }
        }
    }
}
