package dev.moonservice.backend.openmeteo;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class OpenMeteoTransportException extends RuntimeException {
    private final OpenMeteoFailureKind kind;
    private final Integer statusCode;
    private final Duration retryAfter;

    private OpenMeteoTransportException(
            OpenMeteoFailureKind kind,
            Integer statusCode,
            Optional<Duration> retryAfter,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind);
        this.statusCode = statusCode;
        this.retryAfter = Objects.requireNonNull(retryAfter).orElse(null);
    }

    public static OpenMeteoTransportException rateLimited(int statusCode, Optional<Duration> retryAfter) {
        return new OpenMeteoTransportException(
                OpenMeteoFailureKind.RATE_LIMIT,
                statusCode,
                retryAfter,
                "Open-Meteo rate limited the request with HTTP " + statusCode + ".",
                null);
    }

    public static OpenMeteoTransportException transientHttp(int statusCode, Optional<Duration> retryAfter) {
        return new OpenMeteoTransportException(
                OpenMeteoFailureKind.TRANSIENT_HTTP_FAILURE,
                statusCode,
                retryAfter,
                "Open-Meteo returned transient HTTP " + statusCode + ".",
                null);
    }

    public static OpenMeteoTransportException nonRetryableHttp(int statusCode, Optional<Duration> retryAfter) {
        return new OpenMeteoTransportException(
                OpenMeteoFailureKind.NON_RETRYABLE_HTTP_FAILURE,
                statusCode,
                retryAfter,
                "Open-Meteo returned non-retryable HTTP " + statusCode + ".",
                null);
    }

    public static OpenMeteoTransportException ioFailure(Throwable cause) {
        return new OpenMeteoTransportException(
                OpenMeteoFailureKind.IO_FAILURE,
                null,
                Optional.empty(),
                "Open-Meteo request failed.",
                cause);
    }

    public static OpenMeteoTransportException timeout(Throwable cause) {
        return new OpenMeteoTransportException(
                OpenMeteoFailureKind.TIMEOUT,
                null,
                Optional.empty(),
                "Open-Meteo request timed out.",
                cause);
    }

    public boolean canRetry(Duration maxRetryAfter) {
        Objects.requireNonNull(maxRetryAfter);
        if (!kind.isRetryable()) {
            return false;
        }
        return retryAfter()
                .map(delay -> !delay.isNegative() && delay.compareTo(maxRetryAfter) <= 0)
                .orElse(true);
    }

    public OpenMeteoFailureKind kind() {
        return kind;
    }

    public Optional<Integer> statusCode() {
        return Optional.ofNullable(statusCode);
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
