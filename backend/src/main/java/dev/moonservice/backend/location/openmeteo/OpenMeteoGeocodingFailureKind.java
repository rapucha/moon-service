package dev.moonservice.backend.location.openmeteo;

enum OpenMeteoGeocodingFailureKind {
    RATE_LIMIT(true),
    TRANSIENT_HTTP_FAILURE(true),
    NON_RETRYABLE_HTTP_FAILURE(false),
    TIMEOUT(true),
    IO_FAILURE(true),
    INTERRUPTED(false);

    private final boolean retryable;

    OpenMeteoGeocodingFailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    boolean isRetryable() {
        return retryable;
    }
}
