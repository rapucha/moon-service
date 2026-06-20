package dev.moonservice.backend.opportunity;

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
}
