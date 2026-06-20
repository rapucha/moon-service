package dev.moonservice.backend.opportunity;

import java.time.ZoneId;

public record ResolvedLocation(
        String locationId,
        String displayName,
        ZoneId zoneId
) {
}
