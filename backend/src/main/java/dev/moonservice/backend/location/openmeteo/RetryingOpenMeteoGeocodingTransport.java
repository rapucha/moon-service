package dev.moonservice.backend.location.openmeteo;

import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

final class RetryingOpenMeteoGeocodingTransport implements OpenMeteoGeocodingTransport {
    private final OpenMeteoGeocodingTransport delegate;
    private final RetryTemplate retryTemplate;

    RetryingOpenMeteoGeocodingTransport(
            OpenMeteoGeocodingTransport delegate,
            int maxRetries,
            Duration maxRetryAfter
    ) {
        this.delegate = Objects.requireNonNull(delegate);
        this.retryTemplate = new RetryTemplate(new OpenMeteoGeocodingRetryPolicy(maxRetries, maxRetryAfter));
    }

    @Override
    public String get(URI requestUri, Duration timeout) throws OpenMeteoGeocodingTransportException {
        try {
            return retryTemplate.execute(() -> delegate.get(requestUri, timeout));
        } catch (RetryException ex) {
            throw transportException(ex);
        }
    }

    private static OpenMeteoGeocodingTransportException transportException(RetryException ex) {
        Throwable lastException = ex.getLastException();
        if (lastException instanceof OpenMeteoGeocodingTransportException transportException) {
            return transportException;
        }
        return OpenMeteoGeocodingTransportException.ioFailure(ex);
    }
}
