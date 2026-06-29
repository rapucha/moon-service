package dev.moonservice.backend.observability;

public record ProviderQuotaLimits(
        Long hourly,
        Long daily,
        Long monthly
) {
    public ProviderQuotaLimits {
        hourly = requirePositiveOrNull(hourly, "hourly");
        daily = requirePositiveOrNull(daily, "daily");
        monthly = requirePositiveOrNull(monthly, "monthly");
    }

    public static ProviderQuotaLimits unknown() {
        return new ProviderQuotaLimits(null, null, null);
    }

    private static Long requirePositiveOrNull(Long value, String name) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException("Provider quota " + name + " limit must be positive.");
        }
        return value;
    }
}
