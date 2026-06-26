package dev.moonservice.backend.openmeteo;

import org.springframework.core.retry.RetryPolicy;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;
import java.util.Objects;

final class OpenMeteoRetryPolicy implements RetryPolicy {
    private final int maxRetries;
    private final Duration maxRetryAfter;
    private final ThreadLocal<Duration> nextBackOff = ThreadLocal.withInitial(() -> Duration.ZERO);

    OpenMeteoRetryPolicy(int maxRetries, Duration maxRetryAfter) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be zero or greater.");
        }
        this.maxRetries = maxRetries;
        this.maxRetryAfter = Objects.requireNonNull(maxRetryAfter);
    }

    @Override
    public boolean shouldRetry(Throwable throwable) {
        if (throwable instanceof OpenMeteoTransportException ex && ex.canRetry(maxRetryAfter)) {
            nextBackOff.set(ex.retryAfter().orElse(Duration.ZERO));
            return true;
        }
        nextBackOff.remove();
        return false;
    }

    @Override
    public BackOff getBackOff() {
        return () -> new BackOffExecution() {
            private int retries;

            @Override
            public long nextBackOff() {
                if (retries >= maxRetries) {
                    nextBackOff.remove();
                    return STOP;
                }
                retries++;
                Duration delay = nextBackOff.get();
                nextBackOff.remove();
                return delay.toMillis();
            }
        };
    }
}
