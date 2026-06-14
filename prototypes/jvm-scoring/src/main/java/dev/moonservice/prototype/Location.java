package dev.moonservice.prototype;

record Location(
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
}
