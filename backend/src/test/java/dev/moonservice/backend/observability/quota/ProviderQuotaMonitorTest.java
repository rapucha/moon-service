package dev.moonservice.backend.observability.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProviderQuotaMonitorTest {
    @Test
    void representsUnknownLimitsExplicitly() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-29T12:34:56Z"));
        ProviderQuotaMonitor monitor = new ProviderQuotaMonitor(
                clock,
                List.of(new ProviderOperationDefinition(
                        "fictional-location-llm",
                        "example-llm",
                        "fictional-location-resolution",
                        ProviderQuotaLimits.unknown())));

        monitor.operation("fictional-location-llm").recordCall();

        ProviderQuotaMonitor.ProviderQuotaWindowSnapshot hourly = monitor.snapshots()
                .get("fictional-location-llm")
                .usage()
                .hourly();
        assertEquals(1, hourly.used());
        assertNull(hourly.limit());
        assertFalse(hourly.knownLimit());
        assertNull(hourly.percentUsed());
        assertEquals("unknown_limit", hourly.warningState());
    }

    @Test
    void appliesKnownLimitWarningThresholds() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-29T12:34:56Z"));
        ProviderQuotaMonitor monitor = new ProviderQuotaMonitor(
                clock,
                List.of(new ProviderOperationDefinition(
                        "open-meteo-weather",
                        "open-meteo",
                        "weather",
                        new ProviderQuotaLimits(38L, 23L, 20L))));

        for (int i = 0; i < 19; i++) {
            monitor.operation("open-meteo-weather").recordCall();
        }

        ProviderQuotaMonitor.ProviderQuotaUsageSnapshot usage = monitor.snapshots()
                .get("open-meteo-weather")
                .usage();
        assertEquals(50.0, usage.hourly().percentUsed(), 0.0001);
        assertEquals("watch", usage.hourly().warningState());
        assertEquals("warning", usage.daily().warningState());
        assertEquals(95.0, usage.monthly().percentUsed(), 0.0001);
        assertEquals("critical", usage.monthly().warningState());
    }

    @Test
    void resetsCalendarAlignedWindows() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-29T23:30:00Z"));
        ProviderQuotaMonitor monitor = new ProviderQuotaMonitor(
                clock,
                List.of(new ProviderOperationDefinition(
                        "open-meteo-geocoding",
                        "open-meteo",
                        "geocoding",
                        new ProviderQuotaLimits(10L, 10L, 10L))));

        monitor.operation("open-meteo-geocoding").recordCall();
        clock.setInstant(Instant.parse("2026-06-30T00:15:00Z"));

        ProviderQuotaMonitor.ProviderQuotaUsageSnapshot usage = monitor.snapshots()
                .get("open-meteo-geocoding")
                .usage();
        assertEquals(0, usage.hourly().used());
        assertEquals(Instant.parse("2026-06-30T00:00:00Z"), usage.hourly().windowStartedAt());
        assertEquals(0, usage.daily().used());
        assertEquals(Instant.parse("2026-06-30T00:00:00Z"), usage.daily().windowStartedAt());
        assertEquals(1, usage.monthly().used());
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), usage.monthly().windowStartedAt());
    }

    @Test
    void rejectsDuplicateOperationIds() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-29T12:34:56Z"));

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderQuotaMonitor(
                        clock,
                        List.of(
                                new ProviderOperationDefinition(
                                        "duplicate",
                                        "provider-a",
                                        "operation-a",
                                        ProviderQuotaLimits.unknown()),
                                new ProviderOperationDefinition(
                                        "duplicate",
                                        "provider-b",
                                        "operation-b",
                                        ProviderQuotaLimits.unknown()))));

        assertTrue(exception.getMessage().contains("Duplicate provider quota operation id"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
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
