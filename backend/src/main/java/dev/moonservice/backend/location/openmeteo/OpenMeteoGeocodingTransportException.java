package dev.moonservice.backend.location.openmeteo;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

final class OpenMeteoGeocodingTransportException extends RuntimeException {
    private final OpenMeteoGeocodingFailureKind kind;
    private final Integer statusCode;
    private final Duration retryAfter;

    private OpenMeteoGeocodingTransportException(
            OpenMeteoGeocodingFailureKind kind,
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

    static OpenMeteoGeocodingTransportException rateLimited(int statusCode, Optional<Duration> retryAfter) {
        return new OpenMeteoGeocodingTransportException(
                OpenMeteoGeocodingFailureKind.RATE_LIMIT,
                statusCode,
                retryAfter,
                "Open-Meteo Geocoding rate limited the request with HTTP " + statusCode + ".",
                null);
    }

    static OpenMeteoGeocodingTransportException transientHttp(int statusCode, Optional<Duration> retryAfter) {
        return new OpenMeteoGeocodingTransportException(
                OpenMeteoGeocodingFailureKind.TRANSIENT_HTTP_FAILURE,
                statusCode,
                retryAfter,
                "Open-Meteo Geocoding returned transient HTTP " + statusCode + ".",
                null);
    }

    static OpenMeteoGeocodingTransportException nonRetryableHttp(int statusCode, Optional<Duration> retryAfter) {
        return new OpenMeteoGeocodingTransportException(
                OpenMeteoGeocodingFailureKind.NON_RETRYABLE_HTTP_FAILURE,
                statusCode,
                retryAfter,
                "Open-Meteo Geocoding returned non-retryable HTTP " + statusCode + ".",
                null);
    }

    static OpenMeteoGeocodingTransportException ioFailure(Throwable cause) {
        return new OpenMeteoGeocodingTransportException(
                OpenMeteoGeocodingFailureKind.IO_FAILURE,
                null,
                Optional.empty(),
                "Open-Meteo Geocoding request failed.",
                cause);
    }

    static OpenMeteoGeocodingTransportException timeout(Throwable cause) {
        return new OpenMeteoGeocodingTransportException(
                OpenMeteoGeocodingFailureKind.TIMEOUT,
                null,
                Optional.empty(),
                "Open-Meteo Geocoding request timed out.",
                cause);
    }

    boolean canRetry(Duration maxRetryAfter) {
        if (!kind.isRetryable()) {
            return false;
        }
        return retryAfter()
                .map(delay -> !delay.isNegative() && delay.compareTo(maxRetryAfter) <= 0)
                .orElse(true);
    }

    Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    OpenMeteoGeocodingFailureKind kind() {
        return kind;
    }

    Optional<Integer> statusCode() {
        return Optional.ofNullable(statusCode);
    }
}
