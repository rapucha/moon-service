package dev.moonservice.backend.opportunity.search;

import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface OpportunitySearchEngine {
    OpportunitySearchResponse search(OpportunitySearchRequest request);

    default OpportunitySearchResponse search(ResolvedLocation location, OpportunitySearchRequest request) {
        return search(request);
    }

    default OpportunitySearchResponse search(
            ResolvedLocation location,
            OpportunitySearchRequest request,
            Instant notBefore
    ) {
        return search(location, request);
    }

    default PreferenceSearchResult search(
            ResolvedLocation location,
            OpportunitySearchRequest request,
            Instant notBefore,
            OpportunityPreferences preferences
    ) {
        Objects.requireNonNull(notBefore, "notBefore");
        Objects.requireNonNull(preferences, "preferences");
        if (preferences.active()) {
            throw new UnsupportedOperationException(
                    "This opportunity search engine does not support active preferences.");
        }
        return new PreferenceSearchResult(
                search(location, request, notBefore),
                preferences.version(),
                preferences.normalizedFilters(),
                0,
                Map.of());
    }

    record PreferenceSearchResult(
            OpportunitySearchResponse response,
            int appliedPreferenceVersion,
            Map<String, Object> normalizedActiveFilters,
            int excludedSampleCount,
            Map<String, List<AzimuthMatchInterval>> azimuthMatchIntervals
    ) {
        public PreferenceSearchResult {
            Objects.requireNonNull(response, "response");
            if (appliedPreferenceVersion != 1) {
                throw new IllegalArgumentException("appliedPreferenceVersion must be 1.");
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
