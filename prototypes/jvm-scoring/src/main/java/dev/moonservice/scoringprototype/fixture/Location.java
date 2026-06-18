package dev.moonservice.scoringprototype.fixture;

import java.time.ZoneId;

public record Location(
        String slug,
        String kind,
        String id,
        String displayName,
        double latitude,
        double longitude,
        double elevationMeters,
        String timezone,
        String countryCode
) {
    public ZoneId zoneId() {
        return ZoneId.of(timezone);
    }
}
