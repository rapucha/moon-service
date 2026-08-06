package dev.moonservice.backend.opportunity;

import dev.moonservice.backend.location.LocationQuery;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.opportunity.search.LocationCandidatesResponse;
import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchEngine;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest.Order;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.backend.opportunity.search.OpportunityStatusResponse;
import dev.moonservice.backend.weather.WeatherForecastUnavailableException;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

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

    public OpportunityResponse search(String rawQuery, String rawLocationId, Order order) {
        Objects.requireNonNull(order, "order");
        if (order == Order.BEST_MATCH) {
            boolean hasQuery = rawQuery != null && !rawQuery.isBlank();
            boolean hasLocationId = rawLocationId != null && !rawLocationId.isBlank();
            if (hasQuery && hasLocationId) {
                throw new InvalidOpportunitySearchRequestException("Use q or locationId, not both.");
            }
            return hasLocationId
                    ? searchByLocationId(rawLocationId)
                    : searchByQuery(rawQuery);
        }
        return search(rawQuery, rawLocationId, location -> searchResolvedLocation(location, order));
    }

    public OpportunityResponse search(
            String rawQuery,
            String rawLocationId,
            Order order,
            OpportunityPreferences preferences,
            List<String> ignoredPreferenceFields,
            int ignoredPreferenceFieldCount
    ) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(ignoredPreferenceFields, "ignoredPreferenceFields");
        return search(rawQuery, rawLocationId, location -> searchResolvedLocation(
                location, order, preferences, ignoredPreferenceFields, ignoredPreferenceFieldCount));
    }

    private OpportunityResponse search(
            String rawQuery,
            String rawLocationId,
            Function<ResolvedLocation, OpportunityResponse> resolvedSearch
    ) {
        boolean hasQuery = rawQuery != null && !rawQuery.isBlank();
        boolean hasLocationId = rawLocationId != null && !rawLocationId.isBlank();
        if (hasQuery && hasLocationId) {
            throw new InvalidOpportunitySearchRequestException("Use q or locationId, not both.");
        }
        if (hasLocationId) {
            return searchLocationResolution(
                    locationResolver.resolveLocationId(normalizeLocationId(rawLocationId)),
                    resolvedSearch);
        }
        return searchLocationResolution(
                locationResolver.resolve(new LocationQuery(normalizeQuery(rawQuery))),
                resolvedSearch);
    }

    public OpportunityResponse searchByQuery(String rawQuery) {
        String query = normalizeQuery(rawQuery);
        LocationResolution resolution = locationResolver.resolve(new LocationQuery(query));
        return searchLocationResolution(resolution, location -> searchResolvedLocation(location, Order.BEST_MATCH));
    }

    public OpportunityResponse searchByLocationId(String rawLocationId) {
        String locationId = normalizeLocationId(rawLocationId);
        LocationResolution resolution = locationResolver.resolveLocationId(locationId);
        return searchLocationResolution(resolution, location -> searchResolvedLocation(location, Order.BEST_MATCH));
    }

    private OpportunityResponse searchLocationResolution(
            LocationResolution resolution,
            Function<ResolvedLocation, OpportunityResponse> resolvedSearch
    ) {
        if (resolution.isAmbiguous()) {
            return LocationCandidatesResponse.ambiguous(resolution.candidates());
        }
        if (resolution.isTemporarilyUnavailable()) {
            return OpportunityStatusResponse.temporarilyUnavailable();
        }
        return resolution.singleCandidate()
                .<OpportunityResponse>map(resolvedSearch)
                .orElseGet(OpportunityStatusResponse::locationNotFound);
    }

    private OpportunityResponse searchResolvedLocation(ResolvedLocation location, Order order) {
        try {
            Instant notBefore = opportunitySearchDefaults.now();
            return opportunitySearchEngine.search(
                    location,
                    opportunitySearchDefaults.requestFor(location, notBefore, order),
                    notBefore);
        } catch (WeatherForecastUnavailableException ex) {
            return OpportunityStatusResponse.temporarilyUnavailable(
                    "Opportunity weather lookup is temporarily unavailable.");
        }
    }

    private OpportunityResponse searchResolvedLocation(
            ResolvedLocation location,
            Order order,
            OpportunityPreferences preferences,
            List<String> ignoredPreferenceFields,
            int ignoredPreferenceFieldCount
    ) {
        try {
            Instant notBefore = opportunitySearchDefaults.now();
            OpportunitySearchEngine.PreferenceSearchResult result = opportunitySearchEngine.search(
                    location,
                    opportunitySearchDefaults.requestFor(location, notBefore, order),
                    notBefore,
                    preferences);
            return OpportunitySearchResponse.withPreferences(
                    result, ignoredPreferenceFields, ignoredPreferenceFieldCount);
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
