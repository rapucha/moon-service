package dev.moonservice.backend.opportunity.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.moonservice.backend.location.LocationProvider;
import dev.moonservice.backend.location.ProviderLocationId;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.opportunity.InvalidOpportunitySearchRequestException;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;
import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.scoringprototype.PreviewEvaluator;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

class JvmScoringOpportunitySearchEngineTest {
    @Test
    void scoresResolvedLocationCoordinatesWithoutFixtureLocationId() {
        JvmScoringOpportunitySearchEngine engine = new JvmScoringOpportunitySearchEngine(new PreviewEvaluator());

        OpportunitySearchResponse response = engine.search(
                amsterdam(),
                new OpportunitySearchRequest("amsterdam-nl", "2026-06-29", 7, 90.0, 5));

        assertEquals("ok", response.status());
        assertEquals("openmeteo:2759794", response.location().id());
        assertEquals("Amsterdam, North Holland, Netherlands", response.location().displayName());
        assertEquals("Europe/Amsterdam", response.location().timezone());
        assertFalse(response.opportunities().isEmpty());
        assertTrue(response.opportunities().getFirst().id().startsWith("amsterdam-nl-"));
        assertTrue(response.opportunities().getFirst().links().get("ics").startsWith("/o/amsterdam-nl-"));
    }

    @Test
    void translatesDirectPrototypeValidationFailuresToInvalidRequest() {
        JvmScoringOpportunitySearchEngine engine = new JvmScoringOpportunitySearchEngine(new PreviewEvaluator());

        InvalidOpportunitySearchRequestException exception = assertThrows(
                InvalidOpportunitySearchRequestException.class,
                () -> engine.search(new OpportunitySearchRequest("prague-cz", "2026-06-29", 0, 90.0, 5)));

        assertEquals("forecastHorizonDays must be between 1 and 30.", exception.getMessage());
        assertNotNull(exception.getCause());
    }

    @Test
    void treatsResolvedPrototypeValidationFailuresAsInternalInvariants() {
        JvmScoringOpportunitySearchEngine engine = new JvmScoringOpportunitySearchEngine(new PreviewEvaluator());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> engine.search(amsterdam(), new OpportunitySearchRequest("amsterdam-nl", "2026-06-29", 0, 90.0, 5)));

        assertEquals("Resolved opportunity scoring request was invalid.", exception.getMessage());
        assertNotNull(exception.getCause());
    }

    private static ResolvedLocation amsterdam() {
        return new ResolvedLocation(
                "amsterdam-nl",
                new ProviderLocationId(LocationProvider.OPEN_METEO, "2759794"),
                "Amsterdam, North Holland, Netherlands",
                52.37403,
                4.88969,
                13,
                ZoneId.of("Europe/Amsterdam"),
                "NL");
    }
}
