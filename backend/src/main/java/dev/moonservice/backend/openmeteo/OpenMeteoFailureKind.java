package dev.moonservice.backend.openmeteo;

public enum OpenMeteoFailureKind {
    RATE_LIMIT(true),
    TRANSIENT_HTTP_FAILURE(true),
    NON_RETRYABLE_HTTP_FAILURE(false),
    IO_FAILURE(true),
    TIMEOUT(true);

    private final boolean retryable;

    OpenMeteoFailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
