package dev.moonservice.scoringprototype;

import java.time.Instant;

record MoonWindow(
        Location location,
        Instant startsAt,
        MoonSample peak,
        Instant endsAt,
        int sampleCount
) {
    String id() {
        return OpportunityIds.format(location.slug(), peak.instant());
    }
}
