package dev.moonservice.backend.openmeteo;

public enum OpenMeteoRestFailureKind {
    RATE_LIMIT,
    TRANSIENT_HTTP_FAILURE,
    NON_RETRYABLE_HTTP_FAILURE,
    IO_FAILURE,
    TIMEOUT
}
