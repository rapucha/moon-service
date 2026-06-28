package dev.moonservice.backend.openmeteo;

import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

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
        this(delegate, maxRetries, maxRetryAfter, () -> {
        });
    }

    public RetryingOpenMeteoTransport(
            OpenMeteoTransport delegate,
            int maxRetries,
            Duration maxRetryAfter,
            Runnable retryObserver
    ) {
        this.delegate = Objects.requireNonNull(delegate);
        this.retryTemplate = new RetryTemplate(new ObservedRetryPolicy(
                new OpenMeteoRetryPolicy(maxRetries, maxRetryAfter),
                Objects.requireNonNull(retryObserver, "retryObserver")));
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

    private static final class ObservedRetryPolicy implements RetryPolicy {
        private final RetryPolicy delegate;
        private final Runnable retryObserver;

        private ObservedRetryPolicy(RetryPolicy delegate, Runnable retryObserver) {
            this.delegate = delegate;
            this.retryObserver = retryObserver;
        }

        @Override
        public boolean shouldRetry(Throwable throwable) {
            return delegate.shouldRetry(throwable);
        }

        @Override
        public BackOff getBackOff() {
            BackOff backOff = delegate.getBackOff();
            return () -> {
                BackOffExecution execution = backOff.start();
                return () -> {
                    long delayMillis = execution.nextBackOff();
                    if (delayMillis != BackOffExecution.STOP) {
                        retryObserver.run();
                    }
                    return delayMillis;
                };
            };
        }
    }
}
