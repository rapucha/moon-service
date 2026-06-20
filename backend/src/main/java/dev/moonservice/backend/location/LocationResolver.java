package dev.moonservice.backend.location;

import java.util.Optional;

public interface LocationResolver {
    Optional<ResolvedLocation> resolve(String query);
}
