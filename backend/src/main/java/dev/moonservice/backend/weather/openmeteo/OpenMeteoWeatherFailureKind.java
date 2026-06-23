package dev.moonservice.backend.weather.openmeteo;

enum OpenMeteoWeatherFailureKind {
    RATE_LIMIT(true),
    TRANSIENT_HTTP_FAILURE(true),
    NON_RETRYABLE_HTTP_FAILURE(false),
    IO_FAILURE(true),
    TIMEOUT(true);

    private final boolean retryable;

    OpenMeteoWeatherFailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    boolean isRetryable() {
        return retryable;
    }
}
