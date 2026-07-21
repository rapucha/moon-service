package dev.moonservice.backend.admission;

import dev.moonservice.backend.config.MoonRuntimeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public final class HostedAlphaProviderAdmission {
    private final boolean enabled;
    private final Clock clock;
    private final TokenBucket providerLookupBucket;
    private final Semaphore providerOperationPermits;

    HostedAlphaProviderAdmission(
            MoonRuntimeProperties properties,
            Clock clock,
            @Value("${moon.hosted-alpha.enabled:false}") boolean enabled
    ) {
        MoonRuntimeProperties checkedProperties = Objects.requireNonNull(properties, "properties");
        MoonRuntimeProperties.ResourceLimits limits = checkedProperties.getResourceLimits();
        if (enabled && (limits.getProviderLookupCapacity() > 10
                || limits.getProviderLookupRefillInterval().compareTo(Duration.ofMinutes(1)) < 0
                || limits.getOpportunityConcurrency() > 2)) {
            throw new IllegalStateException("Hosted-alpha resource settings weaken the accepted safety bounds");
        }
        if (enabled && checkedProperties.getOpenMeteo().getMaxTransportRetries() > 1) {
            throw new IllegalStateException("Hosted-alpha mode allows at most one Open-Meteo transport retry");
        }
        this.enabled = enabled;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.providerLookupBucket = new TokenBucket(
                limits.getProviderLookupCapacity(),
                limits.getProviderLookupRefillInterval(),
                clock.instant());
        this.providerOperationPermits = new Semaphore(limits.getOpportunityConcurrency(), true);
    }

    public Admission tryAcquire() {
        if (!enabled) {
            return Admission.acquired(null);
        }
        if (!providerOperationPermits.tryAcquire()) {
            return Admission.rejected(1L);
        }

        TokenDecision tokenDecision = providerLookupBucket.consumeTokenIfAvailable(clock.instant());
        if (!tokenDecision.accepted()) {
            providerOperationPermits.release();
            return Admission.rejected(tokenDecision.retryAfterSeconds());
        }
        return Admission.acquired(providerOperationPermits::release);
    }

    public static final class Admission implements AutoCloseable {
        private final boolean accepted;
        private final long retryAfterSeconds;
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Admission(boolean accepted, long retryAfterSeconds, Runnable release) {
            this.accepted = accepted;
            this.retryAfterSeconds = retryAfterSeconds;
            this.release = release;
        }

        private static Admission acquired(Runnable release) {
            return new Admission(true, 0L, release);
        }

        private static Admission rejected(long retryAfterSeconds) {
            return new Admission(false, retryAfterSeconds, null);
        }

        public boolean accepted() {
            return accepted;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }

        @Override
        public void close() {
            if (release != null && closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }

    private record TokenDecision(boolean accepted, long retryAfterSeconds) {
    }

    private static final class TokenBucket {
        private final int capacity;
        private final Duration refillInterval;
        private int tokens;
        private Instant refilledAt;

        private TokenBucket(int capacity, Duration refillInterval, Instant startedAt) {
            this.capacity = capacity;
            this.refillInterval = refillInterval;
            this.tokens = capacity;
            this.refilledAt = startedAt;
        }

        private synchronized TokenDecision consumeTokenIfAvailable(Instant now) {
            refill(now);
            if (tokens > 0) {
                tokens--;
                return new TokenDecision(true, 0L);
            }
            Duration elapsed = now.isBefore(refilledAt) ? Duration.ZERO : Duration.between(refilledAt, now);
            long retryAfterMillis = refillInterval.minus(elapsed).toMillis();
            return new TokenDecision(false, Math.max(1L, Math.floorDiv(retryAfterMillis + 999L, 1_000L)));
        }

        private void refill(Instant now) {
            if (now.isBefore(refilledAt)) {
                return;
            }
            long intervals = Duration.between(refilledAt, now).dividedBy(refillInterval);
            if (intervals == 0L) {
                return;
            }
            tokens = (int) Math.min(capacity, tokens + Math.min(intervals, capacity));
            refilledAt = refilledAt.plus(refillInterval.multipliedBy(intervals));
        }
    }
}
