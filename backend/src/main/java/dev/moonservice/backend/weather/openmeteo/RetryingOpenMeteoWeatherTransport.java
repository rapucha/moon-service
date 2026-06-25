package dev.moonservice.backend.weather.openmeteo;

import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

final class RetryingOpenMeteoWeatherTransport implements OpenMeteoWeatherTransport {
    private final OpenMeteoWeatherTransport delegate;
    private final RetryTemplate retryTemplate;

    RetryingOpenMeteoWeatherTransport(
            OpenMeteoWeatherTransport delegate,
            int maxRetries,
            Duration maxRetryAfter
    ) {
        this.delegate = Objects.requireNonNull(delegate);
        this.retryTemplate = new RetryTemplate(new OpenMeteoWeatherRetryPolicy(maxRetries, maxRetryAfter));
    }

    @Override
    public String get(URI requestUri, Duration timeout) throws OpenMeteoWeatherTransportException {
        try {
            return retryTemplate.execute(() -> delegate.get(requestUri, timeout));
        } catch (RetryException ex) {
            throw transportException(ex);
        }
    }

    private static OpenMeteoWeatherTransportException transportException(RetryException ex) {
        Throwable lastException = ex.getLastException();
        if (lastException instanceof OpenMeteoWeatherTransportException transportException) {
            return transportException;
        }
        return OpenMeteoWeatherTransportException.ioFailure(ex);
    }
}
