package dev.moonservice.backend.feedback;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface CalibrationFeedbackRepository extends AutoCloseable {
    StoreResult store(CalibrationFeedbackReport report);

    LookupResult findByClientSubmissionId(UUID clientSubmissionId);

    RepositoryStatus status();

    DeleteResult deleteByServerReportId(UUID serverReportId);

    @Override
    default void close() {
    }

    static CalibrationFeedbackRepository disabled() {
        return new InactiveCalibrationFeedbackRepository(false);
    }

    static CalibrationFeedbackRepository unavailable() {
        return new InactiveCalibrationFeedbackRepository(true);
    }

    interface StoreResult {
        record Created(UUID serverReportId, Instant submittedAt) implements StoreResult {
            public Created {
                Objects.requireNonNull(serverReportId, "serverReportId");
                Objects.requireNonNull(submittedAt, "submittedAt");
            }
        }

        record Replayed(UUID serverReportId, Instant submittedAt) implements StoreResult {
            public Replayed {
                Objects.requireNonNull(serverReportId, "serverReportId");
                Objects.requireNonNull(submittedAt, "submittedAt");
            }
        }

        record Conflict() implements StoreResult {
        }

        record CapacityRefused() implements StoreResult {
        }

        record Disabled() implements StoreResult {
        }

        record Unavailable() implements StoreResult {
        }
    }

    interface LookupResult {
        record Found(UUID serverReportId, byte[] idempotencyHash, Instant submittedAt) implements LookupResult {
            public Found {
                Objects.requireNonNull(serverReportId, "serverReportId");
                Objects.requireNonNull(submittedAt, "submittedAt");
                if (idempotencyHash == null || idempotencyHash.length != 32) {
                    throw new IllegalArgumentException("idempotencyHash must contain 32 bytes.");
                }
                idempotencyHash = idempotencyHash.clone();
            }

            @Override
            public byte[] idempotencyHash() {
                return idempotencyHash.clone();
            }
        }

        record NotFound() implements LookupResult {
        }

        record Disabled() implements LookupResult {
        }

        record Unavailable() implements LookupResult {
        }
    }

    interface RepositoryStatus {
        record Available(CapacityState state, int used, int capacity, int remaining) implements RepositoryStatus {
            public Available {
                Objects.requireNonNull(state, "state");
                if (capacity <= 0 || used < 0 || remaining != Math.max(0, capacity - used)) {
                    throw new IllegalArgumentException("Capacity counts are inconsistent.");
                }
            }

            public static Available from(int used, int capacity) {
                int nearAt = (capacity * 9 + 9) / 10;
                CapacityState state = used >= capacity
                        ? CapacityState.FULL
                        : used >= nearAt ? CapacityState.NEAR : CapacityState.NORMAL;
                return new Available(state, used, capacity, Math.max(0, capacity - used));
            }
        }

        record Disabled() implements RepositoryStatus {
        }

        record Unavailable() implements RepositoryStatus {
        }
    }

    enum CapacityState {
        NORMAL,
        NEAR,
        FULL
    }

    interface DeleteResult {
        record Deleted() implements DeleteResult {
        }

        record NotFound() implements DeleteResult {
        }

        record Disabled() implements DeleteResult {
        }

        record Unavailable() implements DeleteResult {
        }
    }
}

final class InactiveCalibrationFeedbackRepository implements CalibrationFeedbackRepository {
    private final boolean unavailable;

    InactiveCalibrationFeedbackRepository(boolean unavailable) {
        this.unavailable = unavailable;
    }

    @Override
    public StoreResult store(CalibrationFeedbackReport report) {
        Objects.requireNonNull(report, "report");
        return unavailable ? new StoreResult.Unavailable() : new StoreResult.Disabled();
    }

    @Override
    public LookupResult findByClientSubmissionId(UUID clientSubmissionId) {
        Objects.requireNonNull(clientSubmissionId, "clientSubmissionId");
        return unavailable ? new LookupResult.Unavailable() : new LookupResult.Disabled();
    }

    @Override
    public RepositoryStatus status() {
        return unavailable ? new RepositoryStatus.Unavailable() : new RepositoryStatus.Disabled();
    }

    @Override
    public DeleteResult deleteByServerReportId(UUID serverReportId) {
        Objects.requireNonNull(serverReportId, "serverReportId");
        return unavailable ? new DeleteResult.Unavailable() : new DeleteResult.Disabled();
    }
}
