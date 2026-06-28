package dev.moonservice.backend.opportunity;

import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.opportunity.search.LocationCandidatesResponse;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.backend.opportunity.search.OpportunityStatusResponse;
import dev.moonservice.backend.weather.WeatherForecastUnavailableException;
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

    public OpportunityResponse search(String rawQuery, String rawLocationId) {
        boolean hasQuery = rawQuery != null && !rawQuery.isBlank();
        boolean hasLocationId = rawLocationId != null && !rawLocationId.isBlank();
        if (hasQuery && hasLocationId) {
            throw new InvalidOpportunitySearchRequestException("Use q or locationId, not both.");
        }
        if (hasLocationId) {
            return searchByLocationId(rawLocationId);
        }
        return searchByQuery(rawQuery);
    }

    public OpportunityResponse searchByQuery(String rawQuery) {
        String query = normalizeQuery(rawQuery);
        LocationResolution resolution = locationResolver.resolve(new LocationQuery(query));
        return searchLocationResolution(resolution);
    }

    public OpportunityResponse searchByLocationId(String rawLocationId) {
        String locationId = normalizeLocationId(rawLocationId);
        LocationResolution resolution = locationResolver.resolveLocationId(locationId);
        return searchLocationResolution(resolution);
    }

    private OpportunityResponse searchLocationResolution(LocationResolution resolution) {
        if (resolution.isAmbiguous()) {
            return LocationCandidatesResponse.ambiguous(resolution.candidates());
        }
        if (resolution.isTemporarilyUnavailable()) {
            return OpportunityStatusResponse.temporarilyUnavailable();
        }
        return resolution.singleCandidate()
                .<OpportunityResponse>map(this::searchResolvedLocation)
                .orElseGet(OpportunityStatusResponse::locationNotFound);
    }

    private OpportunityResponse searchResolvedLocation(ResolvedLocation location) {
        try {
            return opportunitySearchEngine.search(
                    location,
                    opportunitySearchDefaults.requestFor(location),
                    opportunitySearchDefaults.now());
        } catch (WeatherForecastUnavailableException ex) {
            return OpportunityStatusResponse.temporarilyUnavailable(
                    "Opportunity weather lookup is temporarily unavailable.");
        }
    }

    private static String normalizeQuery(String rawQuery) {
        if (rawQuery == null) {
            throw new InvalidOpportunitySearchRequestException("q is required.");
        }
        String query = rawQuery.strip().replaceAll("\\s+", " ");
        if (query.isBlank()) {
            throw new InvalidOpportunitySearchRequestException("q must be non-empty.");
        }
        if (containsUnsupportedControlCharacter(query)) {
            throw new InvalidOpportunitySearchRequestException("q contains unsupported control characters.");
        }
        if (query.codePointCount(0, query.length()) > MAX_QUERY_CHARACTERS) {
            throw new InvalidOpportunitySearchRequestException("q must be 100 characters or fewer.");
        }
        return query;
    }

    private static String normalizeLocationId(String rawLocationId) {
        if (rawLocationId == null) {
            throw new InvalidOpportunitySearchRequestException("locationId is required.");
        }
        String locationId = rawLocationId.strip();
        if (locationId.isBlank()) {
            throw new InvalidOpportunitySearchRequestException("locationId must be non-empty.");
        }
        if (containsUnsupportedControlCharacter(locationId)) {
            throw new InvalidOpportunitySearchRequestException("locationId contains unsupported control characters.");
        }
        if (locationId.codePointCount(0, locationId.length()) > MAX_QUERY_CHARACTERS) {
            throw new InvalidOpportunitySearchRequestException("locationId must be 100 characters or fewer.");
        }
        return locationId;
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
