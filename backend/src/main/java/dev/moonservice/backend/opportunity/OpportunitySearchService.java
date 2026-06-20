package dev.moonservice.backend.opportunity;

import dev.moonservice.scoringprototype.UsageException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class OpportunitySearchService {
    private static final int MAX_QUERY_CHARACTERS = 100;

    private final OpportunitySearchEngine opportunitySearchEngine;
    private final LocationResolver locationResolver;
    private final OpportunitySearchDefaults opportunitySearchDefaults;

    public OpportunitySearchService(
            OpportunitySearchEngine opportunitySearchEngine,
            LocationResolver locationResolver,
            OpportunitySearchDefaults opportunitySearchDefaults
    ) {
        this.opportunitySearchEngine = opportunitySearchEngine;
        this.locationResolver = locationResolver;
        this.opportunitySearchDefaults = opportunitySearchDefaults;
    }

    public OpportunitySearchResponse search(JsonNode request) {
        return opportunitySearchEngine.search(OpportunitySearchRequest.fromJson(request));
    }

    public OpportunityResponse searchByQuery(String rawQuery) {
        String query = normalizeQuery(rawQuery);
        return locationResolver.resolve(query)
                .<OpportunityResponse>map(location -> opportunitySearchEngine.search(
                        opportunitySearchDefaults.requestFor(location)))
                .orElseGet(OpportunityStatusResponse::locationNotFound);
    }

    private static String normalizeQuery(String rawQuery) {
        if (rawQuery == null) {
            throw new UsageException("q is required.");
        }
        String query = rawQuery.strip().replaceAll("\\s+", " ");
        if (query.isBlank()) {
            throw new UsageException("q must be non-empty.");
        }
        if (containsUnsupportedControlCharacter(query)) {
            throw new UsageException("q contains unsupported control characters.");
        }
        if (query.codePointCount(0, query.length()) > MAX_QUERY_CHARACTERS) {
            throw new UsageException("q must be 100 characters or fewer.");
        }
        return query;
    }

    private static boolean containsUnsupportedControlCharacter(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint)
                        || codePoint == 0x061C
                        || codePoint == 0x200E
                        || codePoint == 0x200F
                        || codePoint >= 0x202A && codePoint <= 0x202E
                        || codePoint >= 0x2066 && codePoint <= 0x2069);
    }
}
