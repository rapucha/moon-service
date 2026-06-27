package dev.moonservice.backend.location;

import java.time.ZoneId;
import java.util.Objects;

public record ResolvedLocation(
        String locationId,
        ProviderLocationId providerLocationId,
        String displayName,
        double latitude,
        double longitude,
        int elevationMeters,
        ZoneId zoneId,
        String countryCode
) {
    public ResolvedLocation {
        locationId = requireText(locationId, "locationId");
        providerLocationId = Objects.requireNonNull(providerLocationId, "providerLocationId");
        displayName = requireText(displayName, "displayName");
        if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude must be a finite value between -90 and 90.");
        }
        if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude must be a finite value between -180 and 180.");
        }
        zoneId = Objects.requireNonNull(zoneId, "zoneId");
        countryCode = requireText(countryCode, "countryCode");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided.");
        }
        return value.strip();
    }
}
