package dev.moonservice.scoringprototype.window;

import dev.moonservice.scoringprototype.output.OpportunityIds;
import dev.moonservice.scoringprototype.ephemeris.MoonSample;
import dev.moonservice.scoringprototype.fixture.Location;

import java.time.Instant;

public record MoonWindow(
        Location location,
        String kind,
        Instant startsAt,
        MoonSample suggested,
        Instant endsAt
) {
    public String id() {
        return OpportunityIds.format(location.slug(), suggested.instant());
    }
}
