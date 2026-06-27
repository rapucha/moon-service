package dev.moonservice.backend.opportunity.search;

import dev.moonservice.backend.location.ResolvedLocation;
import java.time.Instant;
import java.util.List;

public record LocationCandidatesResponse(
        String status,
        String generatedAt,
        List<LocationCandidate> candidates
) implements OpportunityResponse {
    public LocationCandidatesResponse {
        candidates = List.copyOf(candidates);
    }

    public static LocationCandidatesResponse ambiguous(List<ResolvedLocation> locations) {
        return new LocationCandidatesResponse(
                "ambiguous_location",
                Instant.now().toString(),
                locations.stream()
                        .map(LocationCandidate::from)
                        .toList());
    }

    public record LocationCandidate(
            String kind,
            String id,
            String displayName,
            String countryCode,
            String timezone
    ) {
        static LocationCandidate from(ResolvedLocation location) {
            return new LocationCandidate(
                    "real_location",
                    location.locationId(),
                    location.displayName(),
                    location.countryCode(),
                    location.zoneId().getId());
        }
    }
}
