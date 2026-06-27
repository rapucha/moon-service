package dev.moonservice.backend.opportunity.search;

import java.time.Instant;

public record OpportunityStatusResponse(
        String status,
        String generatedAt,
        String message
) implements OpportunityResponse {
    public static OpportunityStatusResponse locationNotFound() {
        return new OpportunityStatusResponse(
                "location_not_found",
                Instant.now().toString(),
                "No matching location found.");
    }

    public static OpportunityStatusResponse temporarilyUnavailable() {
        return temporarilyUnavailable("Location lookup is temporarily unavailable.");
    }

    public static OpportunityStatusResponse temporarilyUnavailable(String message) {
        return new OpportunityStatusResponse(
                "temporarily_unavailable",
                Instant.now().toString(),
                message);
    }
}
