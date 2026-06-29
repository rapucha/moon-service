package dev.moonservice.backend.observability;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ProviderQuotaMonitor {
    private final Clock clock;
    private final Map<String, ProviderQuotaCounter> operations;

    public ProviderQuotaMonitor(Clock clock, Collection<ProviderOperationDefinition> definitions) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(definitions, "definitions");
        Map<String, ProviderQuotaCounter> counters = new LinkedHashMap<>();
        for (ProviderOperationDefinition definition : definitions) {
            ProviderQuotaCounter previous = counters.put(
                    definition.id(),
                    new ProviderQuotaCounter(definition, this.clock));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate provider quota operation id: " + definition.id());
            }
        }
        this.operations = Collections.unmodifiableMap(new LinkedHashMap<>(counters));
    }

    public ProviderQuotaCounter operation(String operationId) {
        ProviderQuotaCounter counter = operations.get(operationId);
        if (counter == null) {
            throw new IllegalArgumentException("Unknown provider quota operation id: " + operationId);
        }
        return counter;
    }

    public Map<String, ProviderQuotaSnapshot> snapshots() {
        Map<String, ProviderQuotaSnapshot> snapshots = new LinkedHashMap<>();
        operations.forEach((id, counter) -> snapshots.put(id, counter.snapshot()));
        return snapshots;
    }

    public static final class ProviderQuotaCounter {
        private final ProviderOperationDefinition definition;
        private final Clock clock;
        private final CalendarWindowCounter hourly = new CalendarWindowCounter(WindowKind.HOURLY);
        private final CalendarWindowCounter daily = new CalendarWindowCounter(WindowKind.DAILY);
        private final CalendarWindowCounter monthly = new CalendarWindowCounter(WindowKind.MONTHLY);

        private ProviderQuotaCounter(ProviderOperationDefinition definition, Clock clock) {
            this.definition = Objects.requireNonNull(definition, "definition");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        public void recordCall() {
            Instant now = clock.instant();
            hourly.increment(now);
            daily.increment(now);
            monthly.increment(now);
        }

        ProviderQuotaSnapshot snapshot() {
            Instant now = clock.instant();
            ProviderQuotaLimits limits = definition.limits();
            return new ProviderQuotaSnapshot(
                    definition.id(),
                    definition.provider(),
                    definition.operation(),
                    new ProviderQuotaUsageSnapshot(
                            hourly.snapshot(now, limits.hourly()),
                            daily.snapshot(now, limits.daily()),
                            monthly.snapshot(now, limits.monthly())));
        }
    }

    private static final class CalendarWindowCounter {
        private final WindowKind windowKind;
        private Instant windowStartedAt;
        private long used;

        private CalendarWindowCounter(WindowKind windowKind) {
            this.windowKind = windowKind;
        }

        synchronized void increment(Instant now) {
            ensureWindow(now);
            used++;
        }

        synchronized ProviderQuotaWindowSnapshot snapshot(Instant now, Long limit) {
            ensureWindow(now);
            return ProviderQuotaWindowSnapshot.from(windowStartedAt, used, limit);
        }

        private void ensureWindow(Instant now) {
            Instant currentWindow = windowKind.windowStartedAt(now);
            if (!currentWindow.equals(windowStartedAt)) {
                windowStartedAt = currentWindow;
                used = 0;
            }
        }
    }

    private enum WindowKind {
        HOURLY {
            @Override
            Instant windowStartedAt(Instant now) {
                return now.truncatedTo(ChronoUnit.HOURS);
            }
        },
        DAILY {
            @Override
            Instant windowStartedAt(Instant now) {
                return now.atZone(ZoneOffset.UTC)
                        .truncatedTo(ChronoUnit.DAYS)
                        .toInstant();
            }
        },
        MONTHLY {
            @Override
            Instant windowStartedAt(Instant now) {
                ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
                return utc.withDayOfMonth(1)
                        .truncatedTo(ChronoUnit.DAYS)
                        .toInstant();
            }
        };

        abstract Instant windowStartedAt(Instant now);
    }

    public record ProviderQuotaSnapshot(
            String id,
            String provider,
            String operation,
            ProviderQuotaUsageSnapshot usage
    ) {
    }

    public record ProviderQuotaUsageSnapshot(
            ProviderQuotaWindowSnapshot hourly,
            ProviderQuotaWindowSnapshot daily,
            ProviderQuotaWindowSnapshot monthly
    ) {
    }

    public record ProviderQuotaWindowSnapshot(
            Instant windowStartedAt,
            long used,
            Long limit,
            boolean knownLimit,
            Double percentUsed,
            String warningState
    ) {
        static ProviderQuotaWindowSnapshot from(Instant windowStartedAt, long used, Long limit) {
            Double percentUsed = limit == null ? null : used * 100.0 / limit;
            return new ProviderQuotaWindowSnapshot(
                    windowStartedAt,
                    used,
                    limit,
                    limit != null,
                    percentUsed,
                    warningState(percentUsed));
        }

        private static String warningState(Double percentUsed) {
            if (percentUsed == null) {
                return "unknown_limit";
            }
            if (percentUsed >= 100.0) {
                return "exhausted";
            }
            if (percentUsed >= 95.0) {
                return "critical";
            }
            if (percentUsed >= 80.0) {
                return "warning";
            }
            if (percentUsed >= 50.0) {
                return "watch";
            }
            return "ok";
        }
    }
}
