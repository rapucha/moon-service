package dev.moonservice.backend.opportunity;

import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.opportunity.search.OpportunitySearchRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

public class OpportunitySearchDefaults {
    private static final int FORECAST_HORIZON_DAYS = 7;
    private static final double MAX_MOON_ALTITUDE_DEGREES = 90.0;
    private static final int LIMIT = 10;

    private final Clock clock;

    public OpportunitySearchDefaults(Clock clock) {
        this.clock = clock;
    }

    public OpportunitySearchRequest requestFor(
            ResolvedLocation location,
            Instant notBefore,
            OpportunitySearchRequest.Order order
    ) {
        // Reuse the live cutoff instant so a second clock read at local midnight cannot shorten the horizon.
        LocalDate start = notBefore.atZone(location.zoneId()).toLocalDate();
        return new OpportunitySearchRequest(
                location.locationId(),
                start.toString(),
                FORECAST_HORIZON_DAYS,
                MAX_MOON_ALTITUDE_DEGREES,
                LIMIT,
                order);
    }

    public Instant now() {
        return clock.instant();
    }
}
