package dev.moonservice.backend.weather.openmeteo;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

final class OpenMeteoWeatherTransportException extends RuntimeException {
    private final OpenMeteoWeatherFailureKind kind;
    private final Integer statusCode;
    private final Duration retryAfter;

    private OpenMeteoWeatherTransportException(
            OpenMeteoWeatherFailureKind kind,
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

    static OpenMeteoWeatherTransportException rateLimited(int statusCode, Optional<Duration> retryAfter) {
        return new OpenMeteoWeatherTransportException(
                OpenMeteoWeatherFailureKind.RATE_LIMIT,
                statusCode,
                retryAfter,
                "Open-Meteo Weather rate limited the request with HTTP " + statusCode + ".",
                null);
    }

    static OpenMeteoWeatherTransportException transientHttp(int statusCode, Optional<Duration> retryAfter) {
        return new OpenMeteoWeatherTransportException(
                OpenMeteoWeatherFailureKind.TRANSIENT_HTTP_FAILURE,
                statusCode,
                retryAfter,
                "Open-Meteo Weather returned transient HTTP " + statusCode + ".",
                null);
    }

    static OpenMeteoWeatherTransportException nonRetryableHttp(int statusCode, Optional<Duration> retryAfter) {
        return new OpenMeteoWeatherTransportException(
                OpenMeteoWeatherFailureKind.NON_RETRYABLE_HTTP_FAILURE,
                statusCode,
                retryAfter,
                "Open-Meteo Weather returned non-retryable HTTP " + statusCode + ".",
                null);
    }

    static OpenMeteoWeatherTransportException ioFailure(Throwable cause) {
        return new OpenMeteoWeatherTransportException(
                OpenMeteoWeatherFailureKind.IO_FAILURE,
                null,
                Optional.empty(),
                "Open-Meteo Weather request failed.",
                cause);
    }

    static OpenMeteoWeatherTransportException timeout(Throwable cause) {
        return new OpenMeteoWeatherTransportException(
                OpenMeteoWeatherFailureKind.TIMEOUT,
                null,
                Optional.empty(),
                "Open-Meteo Weather request timed out.",
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

    OpenMeteoWeatherFailureKind kind() {
        return kind;
    }

    Optional<Integer> statusCode() {
        return Optional.ofNullable(statusCode);
    }

    Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
