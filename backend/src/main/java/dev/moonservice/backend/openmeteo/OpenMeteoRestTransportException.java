package dev.moonservice.backend.openmeteo;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class OpenMeteoRestTransportException extends RuntimeException {
    private final OpenMeteoRestFailureKind kind;
    private final Integer statusCode;
    private final Duration retryAfter;

    private OpenMeteoRestTransportException(
            OpenMeteoRestFailureKind kind,
            Integer statusCode,
            Optional<Duration> retryAfter,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind);
        this.statusCode = statusCode;
        this.retryAfter = retryAfter.orElse(null);
    }

    static OpenMeteoRestTransportException rateLimited(int statusCode, Optional<Duration> retryAfter) {
        return new OpenMeteoRestTransportException(
                OpenMeteoRestFailureKind.RATE_LIMIT,
                statusCode,
                retryAfter,
                "Open-Meteo rate limited the request with HTTP " + statusCode + ".",
                null);
    }

    static OpenMeteoRestTransportException transientHttp(int statusCode, Optional<Duration> retryAfter) {
        return new OpenMeteoRestTransportException(
                OpenMeteoRestFailureKind.TRANSIENT_HTTP_FAILURE,
                statusCode,
                retryAfter,
                "Open-Meteo returned transient HTTP " + statusCode + ".",
                null);
    }

    static OpenMeteoRestTransportException nonRetryableHttp(int statusCode, Optional<Duration> retryAfter) {
        return new OpenMeteoRestTransportException(
                OpenMeteoRestFailureKind.NON_RETRYABLE_HTTP_FAILURE,
                statusCode,
                retryAfter,
                "Open-Meteo returned non-retryable HTTP " + statusCode + ".",
                null);
    }

    static OpenMeteoRestTransportException ioFailure(Throwable cause) {
        return new OpenMeteoRestTransportException(
                OpenMeteoRestFailureKind.IO_FAILURE,
                null,
                Optional.empty(),
                "Open-Meteo request failed.",
                cause);
    }

    static OpenMeteoRestTransportException timeout(Throwable cause) {
        return new OpenMeteoRestTransportException(
                OpenMeteoRestFailureKind.TIMEOUT,
                null,
                Optional.empty(),
                "Open-Meteo request timed out.",
                cause);
    }

    public OpenMeteoRestFailureKind kind() {
        return kind;
    }

    public Optional<Integer> statusCode() {
        return Optional.ofNullable(statusCode);
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
