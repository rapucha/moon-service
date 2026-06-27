package dev.moonservice.backend.location;

public interface LocationResolver {
    LocationResolution resolve(LocationQuery query);

    default LocationResolution resolveLocationId(String locationId) {
        return LocationResolution.notFound();
    }
}
