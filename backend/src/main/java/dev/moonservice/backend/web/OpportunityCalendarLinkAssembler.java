package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.search.OpportunityResponse;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest.Order;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.scoringprototype.input.OpportunityPreferences;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class OpportunityCalendarLinkAssembler {
    private OpportunityCalendarLinkAssembler() {
    }

    static OpportunityResponse withCalendarLinks(
            OpportunityResponse source,
            Order order,
            ProductWeatherRanking weatherRanking,
            OpportunityPreferences preferences
    ) {
        if (!(source instanceof OpportunitySearchResponse response)) {
            return source;
        }
        String query = PublicPreferenceQuery.calendarQuery(
                response.location().id(), order, weatherRanking, preferences);
        return new OpportunitySearchResponse(
                response.status(),
                response.generatedAt(),
                response.location(),
                response.forecastHorizonDays(),
                response.startsAt(),
                response.endsAt(),
                response.candidateWindowsEvaluated(),
                response.maxMoonAltitudeDegrees(),
                response.opportunities().stream()
                        .map(opportunity -> withCalendarLink(opportunity, query))
                        .toList(),
                response.rejected(),
                response.messages(),
                response.appliedWeatherRanking(),
                response.appliedPreferenceVersion(),
                response.normalizedActiveFilters(),
                response.excludedSampleCount(),
                response.ignoredPreferenceFields(),
                response.ignoredPreferenceFieldCount(),
                response.additionalIgnoredPreferenceFieldCount(),
                response.emptyReason(),
                response.preferenceImpact(),
                response.asOf(),
                response.currentMoon());
    }

    private static OpportunitySearchResponse.Opportunity withCalendarLink(
            OpportunitySearchResponse.Opportunity opportunity,
            String query
    ) {
        Map<String, String> links = new LinkedHashMap<>();
        if (opportunity.links() != null) {
            links.putAll(opportunity.links());
        }
        links.put("ics", "/o/" + opportunity.id() + ".ics" + query);
        return new OpportunitySearchResponse.Opportunity(
                opportunity.id(),
                opportunity.windowKind(),
                opportunity.moonPass(),
                opportunity.startsAt(),
                opportunity.suggestedAt(),
                opportunity.endsAt(),
                opportunity.localTimeZone(),
                opportunity.score(),
                opportunity.confidence(),
                opportunity.components(),
                opportunity.scoreBasis(),
                opportunity.moon(),
                opportunity.moonPath(),
                opportunity.sun(),
                opportunity.weather(),
                opportunity.exposureBalance(),
                opportunity.reason(),
                Collections.unmodifiableMap(links));
    }
}
