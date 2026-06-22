package dev.moonservice.backend.location;

import java.util.Objects;

public record ProviderLocationId(LocationProvider provider, String providerId) {
    public ProviderLocationId {
        provider = Objects.requireNonNull(provider, "provider");
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must be provided.");
        }
        providerId = providerId.strip();
        if (providerId.contains(":")) {
            throw new IllegalArgumentException("providerId must not contain ':'.");
        }
    }

    public String serialized() {
        return provider.id() + ":" + providerId;
    }
}
