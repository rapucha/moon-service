package dev.moonservice.backend.location;

public interface LocationResolver {
    LocationResolution resolve(LocationQuery query);
}
