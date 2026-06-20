package dev.moonservice.backend.opportunity;

import java.util.Optional;

public interface LocationResolver {
    Optional<ResolvedLocation> resolve(String query);
}
