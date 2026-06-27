package dev.moonservice.backend.openmeteo;

import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public final class RetryingOpenMeteoTransport implements OpenMeteoTransport {
    private final OpenMeteoTransport delegate;
    private final RetryTemplate retryTemplate;

    public RetryingOpenMeteoTransport(
            OpenMeteoTransport delegate,
            int maxRetries,
            Duration maxRetryAfter
    ) {
        this.delegate = Objects.requireNonNull(delegate);
        this.retryTemplate = new RetryTemplate(new OpenMeteoRetryPolicy(maxRetries, maxRetryAfter));
    }

    @Override
    public String get(URI requestUri) throws OpenMeteoTransportException {
        try {
            return retryTemplate.execute(() -> delegate.get(requestUri));
        } catch (RetryException ex) {
            throw transportException(ex);
        }
    }

    private static OpenMeteoTransportException transportException(RetryException ex) {
        Throwable lastException = ex.getLastException();
        if (lastException instanceof OpenMeteoTransportException transportException) {
            return transportException;
        }
        return OpenMeteoTransportException.ioFailure(ex);
    }
}
