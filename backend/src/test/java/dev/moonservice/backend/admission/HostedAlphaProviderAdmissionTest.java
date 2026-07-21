package dev.moonservice.backend.admission;

import dev.moonservice.backend.config.MoonRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostedAlphaProviderAdmissionTest {
    @Test
    void disabledAdmissionIsAlwaysAcceptedWithoutConsumingState() {
        HostedAlphaProviderAdmission admission = admission(properties(1, Duration.ofMinutes(1), 1), false);

        HostedAlphaProviderAdmission.Admission first = admission.tryAcquire();
        HostedAlphaProviderAdmission.Admission second = admission.tryAcquire();
        HostedAlphaProviderAdmission.Admission third = admission.tryAcquire();

        assertAccepted(first);
        assertAccepted(second);
        assertAccepted(third);
        first.close();
        second.close();
        third.close();
    }

    @Test
    void enforcesProviderBurstRefillAndRetryArithmetic() {
        MutableClock clock = new MutableClock();
        HostedAlphaProviderAdmission admission = admission(
                properties(2, Duration.ofMinutes(1), 1),
                clock,
                true);

        acquireAndClose(admission);
        acquireAndClose(admission);
        assertRejected(admission.tryAcquire(), 60L);

        clock.advance(Duration.ofMillis(500));
        assertRejected(admission.tryAcquire(), 60L);
        clock.advance(Duration.ofMillis(500));
        assertRejected(admission.tryAcquire(), 59L);
        clock.advance(Duration.ofSeconds(58));
        assertRejected(admission.tryAcquire(), 1L);
        clock.advance(Duration.ofMillis(999));
        assertRejected(admission.tryAcquire(), 1L);
        clock.advance(Duration.ofMillis(1));
        acquireAndClose(admission);

        clock.advance(Duration.ofMinutes(2));
        acquireAndClose(admission);
        acquireAndClose(admission);
        assertRejected(admission.tryAcquire(), 60L);
    }

    @Test
    void preservesHostedProviderDayBoundAndConservativeAttemptArithmetic() {
        MutableClock clock = new MutableClock();
        HostedAlphaProviderAdmission admission = admission(
                properties(10, Duration.ofMinutes(1), 2),
                clock,
                true);
        int accepted = 0;

        for (int request = 0; request < 10; request++) {
            acquireAndClose(admission);
            accepted++;
        }
        assertRejected(admission.tryAcquire(), 60L);
        for (int minute = 1; minute <= 1_440; minute++) {
            clock.advance(Duration.ofMinutes(1));
            acquireAndClose(admission);
            accepted++;
            assertRejected(admission.tryAcquire(), 60L);
        }

        assertThat(accepted).isEqualTo(1_450);
        int worstCaseAttempts = accepted * 4;
        assertThat(worstCaseAttempts).isEqualTo(5_800);
        assertThat(10_000 - worstCaseAttempts).isEqualTo(4_200);
    }

    @Test
    void rejectsConcurrentAdmissionAndReleasesAcceptedPermit() {
        HostedAlphaProviderAdmission admission = admission(properties(2, Duration.ofMinutes(1), 1), true);

        HostedAlphaProviderAdmission.Admission first = admission.tryAcquire();
        assertAccepted(first);
        assertRejected(admission.tryAcquire(), 1L);

        first.close();
        HostedAlphaProviderAdmission.Admission afterRelease = admission.tryAcquire();
        assertAccepted(afterRelease);
        afterRelease.close();
        assertRejected(admission.tryAcquire(), 60L);
    }

    @Test
    void tokenRefusalReleasesConcurrencyPermit() {
        MutableClock clock = new MutableClock();
        HostedAlphaProviderAdmission admission = admission(
                properties(1, Duration.ofMinutes(1), 1),
                clock,
                true);

        acquireAndClose(admission);
        assertRejected(admission.tryAcquire(), 60L);

        clock.advance(Duration.ofMinutes(1));
        acquireAndClose(admission);
    }

    @Test
    void closingAnAdmissionMoreThanOnceDoesNotReleaseExtraPermits() {
        HostedAlphaProviderAdmission admission = admission(properties(10, Duration.ofMinutes(1), 1), true);

        HostedAlphaProviderAdmission.Admission first = admission.tryAcquire();
        assertAccepted(first);
        first.close();
        first.close();

        HostedAlphaProviderAdmission.Admission second = admission.tryAcquire();
        assertAccepted(second);
        assertRejected(admission.tryAcquire(), 1L);
        second.close();
    }

    @Test
    void validatesHostedProviderBoundsWithoutOwningWholeSiteBounds() {
        MoonRuntimeProperties providerCapacity = properties(11, Duration.ofMinutes(1), 2);
        assertThatThrownBy(() -> admission(providerCapacity, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hosted-alpha resource settings weaken the accepted safety bounds");

        MoonRuntimeProperties providerRefill = properties(10, Duration.ofSeconds(59), 2);
        assertThatThrownBy(() -> admission(providerRefill, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hosted-alpha resource settings weaken the accepted safety bounds");

        MoonRuntimeProperties concurrency = properties(10, Duration.ofMinutes(1), 3);
        assertThatThrownBy(() -> admission(concurrency, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hosted-alpha resource settings weaken the accepted safety bounds");

        MoonRuntimeProperties transportRetries = properties(10, Duration.ofMinutes(1), 2);
        transportRetries.getOpenMeteo().setMaxTransportRetries(2);
        assertThatThrownBy(() -> admission(transportRetries, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hosted-alpha mode allows at most one Open-Meteo transport retry");

        MoonRuntimeProperties wholeSite = properties(10, Duration.ofMinutes(1), 2);
        wholeSite.getResourceLimits().setWholeSiteCapacity(41);
        wholeSite.getResourceLimits().setWholeSiteRefillInterval(Duration.ofMillis(500));
        HostedAlphaProviderAdmission accepted = admission(wholeSite, true);
        acquireAndClose(accepted);
    }

    private static HostedAlphaProviderAdmission admission(
            MoonRuntimeProperties properties,
            boolean enabled
    ) {
        return admission(properties, new MutableClock(), enabled);
    }

    private static HostedAlphaProviderAdmission admission(
            MoonRuntimeProperties properties,
            Clock clock,
            boolean enabled
    ) {
        return new HostedAlphaProviderAdmission(properties, clock, enabled);
    }

    private static MoonRuntimeProperties properties(
            int providerCapacity,
            Duration providerRefill,
            int concurrency
    ) {
        MoonRuntimeProperties properties = new MoonRuntimeProperties();
        properties.getResourceLimits().setProviderLookupCapacity(providerCapacity);
        properties.getResourceLimits().setProviderLookupRefillInterval(providerRefill);
        properties.getResourceLimits().setOpportunityConcurrency(concurrency);
        return properties;
    }

    private static void acquireAndClose(HostedAlphaProviderAdmission admission) {
        HostedAlphaProviderAdmission.Admission acquired = admission.tryAcquire();
        assertAccepted(acquired);
        acquired.close();
    }

    private static void assertAccepted(HostedAlphaProviderAdmission.Admission admission) {
        assertThat(admission.accepted()).isTrue();
        assertThat(admission.retryAfterSeconds()).isZero();
    }

    private static void assertRejected(
            HostedAlphaProviderAdmission.Admission admission,
            long retryAfterSeconds
    ) {
        assertThat(admission.accepted()).isFalse();
        assertThat(admission.retryAfterSeconds()).isEqualTo(retryAfterSeconds);
        admission.close();
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-13T00:00:00Z");

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
