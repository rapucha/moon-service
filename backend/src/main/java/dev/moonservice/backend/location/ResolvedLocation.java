package dev.moonservice.backend.location;

import java.time.ZoneId;

public record ResolvedLocation(
        String locationId,
        String displayName,
        ZoneId zoneId
) {
}
