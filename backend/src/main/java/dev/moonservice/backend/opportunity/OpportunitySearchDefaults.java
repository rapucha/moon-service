package dev.moonservice.backend.opportunity;

import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

public class OpportunitySearchDefaults {
    private static final int FORECAST_HORIZON_DAYS = 7;
    private static final double MAX_MOON_ALTITUDE_DEGREES = 90.0;
    private static final int LIMIT = 5;

    private final Clock clock;

    public OpportunitySearchDefaults(Clock clock) {
        this.clock = clock;
    }

    public OpportunitySearchRequest requestFor(ResolvedLocation location) {
        LocalDate start = LocalDate.now(clock.withZone(location.zoneId()));
        return new OpportunitySearchRequest(
                location.locationId(),
                start.toString(),
                FORECAST_HORIZON_DAYS,
                MAX_MOON_ALTITUDE_DEGREES,
                LIMIT);
    }

    public Instant now() {
        return clock.instant();
    }
}
