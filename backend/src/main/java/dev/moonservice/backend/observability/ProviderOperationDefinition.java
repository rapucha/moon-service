package dev.moonservice.backend.observability;

import java.util.Objects;

public record ProviderOperationDefinition(
        String id,
        String provider,
        String operation,
        ProviderQuotaLimits limits
) {
    public ProviderOperationDefinition {
        id = requireNonBlank(id, "id");
        provider = requireNonBlank(provider, "provider");
        operation = requireNonBlank(operation, "operation");
        limits = Objects.requireNonNullElseGet(limits, ProviderQuotaLimits::unknown);
    }

    private static String requireNonBlank(String value, String name) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Provider operation " + name + " must not be blank.");
        }
        return normalized;
    }
}
