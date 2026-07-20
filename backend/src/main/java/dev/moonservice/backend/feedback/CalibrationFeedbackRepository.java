package dev.moonservice.backend.feedback;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns the optional PostgreSQL store for calibration feedback.
 *
 * <p>Spring creates one instance through {@link FeedbackPersistenceConfiguration}. The instance
 * has one fixed startup mode: disabled by configuration, unavailable because startup setup failed,
 * or active with a private connection pool. Inactive instances deliberately have no JDBC objects,
 * but still return typed results so callers do not need a nullable or optional repository bean.
 *
 * <p>Public operations convert database and transaction failures into {@code Unavailable} results.
 * An active instance does not permanently change mode after a transient failure. Keeping its pool
 * open lets a later call recover when PostgreSQL becomes reachable again.
 *
 * <p>Creates and deletes run under one database transaction and lock the singleton capacity row.
 * That lock serializes changes to the stored count. It also makes the capacity check and report
 * insert or delete one atomic operation across concurrent application threads and instances.
 *
 * <p>Callers should branch on the returned result type. They should not use exceptions to detect
 * disabled storage, an outage, an idempotent replay, a changed-payload conflict, or a full store.
 */
public final class CalibrationFeedbackRepository implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalibrationFeedbackRepository.class);
    private static final Map<String, ?> NO_PARAMETERS = Map.of();

    /*
     * A separate counter avoids counting the full report table for every write. Store and delete
     * lock its single row before changing either the reports or the count, so both stay consistent.
     */
    private static final String COUNT_SQL = """
            SELECT report_count
            FROM calibration_feedback_capacity
            WHERE singleton = TRUE
            """;
    private static final String LOCK_COUNT_SQL = COUNT_SQL + " FOR UPDATE";
    private static final String FIND_SQL = """
            SELECT server_report_id, idempotency_hash, submitted_at
            FROM calibration_feedback_report
            WHERE client_submission_id = :clientSubmissionId
            """;
    private static final String INSERT_SQL = """
            INSERT INTO calibration_feedback_report (
                server_report_id,
                client_submission_id,
                schema_version,
                opportunity_id,
                location_id,
                ambient_light,
                crescent_visibility,
                notes,
                moon_altitude_degrees,
                moon_illumination_percent,
                sun_altitude_degrees,
                light_bucket,
                application_revision,
                idempotency_hash,
                submitted_at
            ) VALUES (
                :serverReportId,
                :clientSubmissionId,
                :schemaVersion,
                :opportunityId,
                :locationId,
                :ambientLight,
                :crescentVisibility,
                :notes,
                :moonAltitudeDegrees,
                :moonIlluminationPercent,
                :sunAltitudeDegrees,
                :lightBucket,
                :applicationRevision,
                :idempotencyHash,
                :submittedAt
            )
            """;
    private static final String INCREMENT_SQL = """
            UPDATE calibration_feedback_capacity
            SET report_count = report_count + 1
            WHERE singleton = TRUE
            """;
    private static final String DELETE_SQL = """
            DELETE FROM calibration_feedback_report
            WHERE server_report_id = :serverReportId
            """;
    private static final String DECREMENT_SQL = """
            UPDATE calibration_feedback_capacity
            SET report_count = report_count - 1
            WHERE singleton = TRUE AND report_count > 0
            """;

    // The mode is selected once at startup. Runtime outages do not rewrite it.
    private final Mode mode;

    // These resources exist only in ACTIVE mode and are all backed by the same private data source.
    private final HikariDataSource dataSource;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    // Capacity is meaningful only in ACTIVE mode; inactive instances never read this zero value.
    private final int capacity;

    /** Builds a resource-free instance for a deliberate disablement or a failed startup setup. */
    private CalibrationFeedbackRepository(Mode mode) {
        if (mode == Mode.ACTIVE) {
            throw new IllegalArgumentException("An active repository requires a data source.");
        }
        this.mode = Objects.requireNonNull(mode, "mode");
        this.dataSource = null;
        this.jdbc = null;
        this.transactions = null;
        this.capacity = 0;
    }

    /**
     * Builds the active instance after configuration has created the pool and completed migrations.
     * The JDBC helper and transaction manager share this data source, so Spring binds their work to
     * the same connection inside each transaction.
     */
    CalibrationFeedbackRepository(HikariDataSource dataSource, int capacity) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive.");
        }
        this.mode = Mode.ACTIVE;
        this.capacity = capacity;
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** Used when persistence is not enabled or required connection settings are incomplete. */
    static CalibrationFeedbackRepository disabled() {
        return new CalibrationFeedbackRepository(Mode.DISABLED);
    }

    /** Used when a complete opt-in fails validation, connection, or migration setup. */
    static CalibrationFeedbackRepository unavailable() {
        return new CalibrationFeedbackRepository(Mode.UNAVAILABLE);
    }

    /**
     * Stores one validated report or explains why no new row was created.
     *
     * <p>The transaction repeats the idempotency lookup even if a caller already performed an early
     * lookup. This closes the race between concurrent requests with the same client submission ID.
     * Exact replay and changed-payload conflict are checked before capacity, so they remain stable
     * even when the store has filled since the original submission.
     */
    public StoreResult store(CalibrationFeedbackReport report) {
        Objects.requireNonNull(report, "report");
        if (mode == Mode.DISABLED) {
            return new StoreResult.Disabled();
        }
        if (mode == Mode.UNAVAILABLE) {
            return new StoreResult.Unavailable();
        }
        try {
            StoreExecution execution = Objects.requireNonNull(
                    transactions.execute(ignored -> storeInTransaction(report)),
                    "Store transaction returned no result.");
            if (execution.previousState() != execution.currentStatus().state()
                    && execution.currentStatus().state() != CapacityState.NORMAL) {
                warnCapacity("transition", execution.currentStatus());
            }
            return execution.result();
        } catch (DataAccessException | TransactionException exception) {
            return new StoreResult.Unavailable();
        }
    }

    /**
     * Performs the cheap lookup used to recognize an earlier client submission.
     *
     * <p>The result contains only the fields needed for replay/conflict handling. The returned hash
     * is defensively copied by {@link LookupResult.Found}; callers cannot mutate repository state.
     * {@link #store(CalibrationFeedbackReport)} still performs the authoritative transactional check.
     */
    public LookupResult findByClientSubmissionId(UUID clientSubmissionId) {
        Objects.requireNonNull(clientSubmissionId, "clientSubmissionId");
        if (mode == Mode.DISABLED) {
            return new LookupResult.Disabled();
        }
        if (mode == Mode.UNAVAILABLE) {
            return new LookupResult.Unavailable();
        }
        try {
            StoredSubmission existing = find(clientSubmissionId);
            return existing == null
                    ? new LookupResult.NotFound()
                    : new LookupResult.Found(
                            existing.serverReportId(), existing.idempotencyHash(), existing.submittedAt());
        } catch (DataAccessException exception) {
            return new LookupResult.Unavailable();
        }
    }

    /**
     * Returns a best-effort operational snapshot without locking writers.
     * A concurrent create or delete may make the count stale immediately after this call.
     */
    public RepositoryStatus status() {
        if (mode == Mode.DISABLED) {
            return new RepositoryStatus.Disabled();
        }
        if (mode == Mode.UNAVAILABLE) {
            return new RepositoryStatus.Unavailable();
        }
        try {
            return RepositoryStatus.Available.from(countReports(), capacity);
        } catch (DataAccessException exception) {
            return new RepositoryStatus.Unavailable();
        }
    }

    /**
     * Deletes one report and decrements the shared count in the same transaction.
     * A missing report is a normal typed outcome and never changes the count.
     */
    public DeleteResult deleteByServerReportId(UUID serverReportId) {
        Objects.requireNonNull(serverReportId, "serverReportId");
        if (mode == Mode.DISABLED) {
            return new DeleteResult.Disabled();
        }
        if (mode == Mode.UNAVAILABLE) {
            return new DeleteResult.Unavailable();
        }
        try {
            return Objects.requireNonNull(transactions.execute(ignored -> deleteInTransaction(serverReportId)));
        } catch (DataAccessException | TransactionException exception) {
            return new DeleteResult.Unavailable();
        }
    }

    /** Emits one aggregate-only warning when enabled storage starts near or at capacity. */
    void warnIfNearOrFullAtStartup() {
        RepositoryStatus repositoryStatus = status();
        if (repositoryStatus instanceof RepositoryStatus.Available available
                && available.state() != CapacityState.NORMAL) {
            warnCapacity("startup", available);
        }
    }

    /**
     * Releases the private pool during Spring shutdown. Inactive instances own no pool, so closing
     * them is intentionally a no-op.
     */
    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private StoreExecution storeInTransaction(CalibrationFeedbackReport report) {
        // Locking first gives this transaction a stable position in the create/delete sequence.
        int used = lockAndCountReports();
        RepositoryStatus.Available previousStatus = RepositoryStatus.Available.from(used, capacity);

        // Replay/conflict must win over capacity refusal, including when the repository is full.
        StoredSubmission existing = find(report.clientSubmissionId());
        if (existing != null) {
            // Use content comparison because byte-array equals would compare object identity.
            StoreResult result = MessageDigest.isEqual(existing.idempotencyHash(), report.idempotencyHash())
                    ? new StoreResult.Replayed(existing.serverReportId(), existing.submittedAt())
                    : new StoreResult.Conflict();
            return new StoreExecution(result, previousStatus.state(), previousStatus);
        }
        if (used >= capacity) {
            return new StoreExecution(new StoreResult.CapacityRefused(), previousStatus.state(), previousStatus);
        }

        // The insert and counter update commit or roll back together under TransactionTemplate.
        UUID serverReportId = UUID.randomUUID();
        int inserted = jdbc.update(INSERT_SQL, parameters(serverReportId, report));
        int incremented = jdbc.update(INCREMENT_SQL, NO_PARAMETERS);
        if (inserted != 1 || incremented != 1) {
            throw new DataAccessResourceFailureException("Feedback write did not update expected rows.");
        }
        RepositoryStatus.Available currentStatus = RepositoryStatus.Available.from(used + 1, capacity);
        return new StoreExecution(
                new StoreResult.Created(serverReportId, report.submittedAt()),
                previousStatus.state(),
                currentStatus);
    }

    private DeleteResult deleteInTransaction(UUID serverReportId) {
        // Use the same lock as store so a delete cannot race a capacity decision or count update.
        lockAndCountReports();
        int deleted = jdbc.update(DELETE_SQL, Map.of("serverReportId", serverReportId));
        if (deleted == 0) {
            return new DeleteResult.NotFound();
        }
        if (deleted != 1 || jdbc.update(DECREMENT_SQL, NO_PARAMETERS) != 1) {
            throw new DataAccessResourceFailureException("Feedback delete did not update expected rows.");
        }
        return new DeleteResult.Deleted();
    }

    /** Locks the singleton counter row for the remainder of the current transaction. */
    private int lockAndCountReports() {
        Integer count = jdbc.queryForObject(LOCK_COUNT_SQL, NO_PARAMETERS, Integer.class);
        if (count == null) {
            throw new DataAccessResourceFailureException("Feedback capacity row is missing.");
        }
        return count;
    }

    /** Reads capacity for status reporting without blocking creates or deletes. */
    private int countReports() {
        Integer count = jdbc.queryForObject(COUNT_SQL, NO_PARAMETERS, Integer.class);
        if (count == null) {
            throw new DataAccessResourceFailureException("Feedback capacity row is missing.");
        }
        return count;
    }

    /** Returns the minimal stored identity needed to decide replay versus conflict. */
    private StoredSubmission find(UUID clientSubmissionId) {
        return jdbc.query(FIND_SQL, Map.of("clientSubmissionId", clientSubmissionId), resultSet -> resultSet.next()
                ? new StoredSubmission(
                        resultSet.getObject("server_report_id", UUID.class),
                        resultSet.getBytes("idempotency_hash"),
                        resultSet.getObject("submitted_at", OffsetDateTime.class).toInstant())
                : null);
    }

    /**
     * Converts the already validated domain report into explicit JDBC values. Nullable answers use
     * SQL VARCHAR types, enum names are the stored contract, and receipt time is written in UTC.
     */
    private static MapSqlParameterSource parameters(UUID serverReportId, CalibrationFeedbackReport report) {
        CalibrationFeedbackReport.AstronomyFacts astronomy = report.astronomyFacts();
        return new MapSqlParameterSource()
                .addValue("serverReportId", serverReportId)
                .addValue("clientSubmissionId", report.clientSubmissionId())
                .addValue("schemaVersion", report.schemaVersion())
                .addValue("opportunityId", report.opportunityId())
                .addValue("locationId", report.locationId())
                .addValue(
                        "ambientLight",
                        report.ambientLight() == null ? null : report.ambientLight().name(),
                        Types.VARCHAR)
                .addValue(
                        "crescentVisibility",
                        report.crescentVisibility() == null ? null : report.crescentVisibility().name(),
                        Types.VARCHAR)
                .addValue("notes", report.notes(), Types.VARCHAR)
                .addValue("moonAltitudeDegrees", astronomy.moonAltitudeDegrees())
                .addValue("moonIlluminationPercent", astronomy.moonIlluminationPercent())
                .addValue("sunAltitudeDegrees", astronomy.sunAltitudeDegrees())
                .addValue("lightBucket", astronomy.lightBucket().name())
                .addValue("applicationRevision", report.applicationRevision())
                .addValue("idempotencyHash", report.idempotencyHash())
                .addValue("submittedAt", utc(report.submittedAt()), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // Warnings contain only aggregate capacity information, never report or client identifiers.
    private static void warnCapacity(String event, RepositoryStatus.Available status) {
        LOGGER.warn(
                "feedback_storage_capacity event={} state={} used={} capacity={} remaining={}",
                event,
                status.state().name().toLowerCase(java.util.Locale.ROOT),
                status.used(),
                status.capacity(),
                status.remaining());
    }

    /** Describes why this fixed repository instance can or cannot access PostgreSQL. */
    private enum Mode {
        /** Persistence was not enabled or required connection settings were incomplete. */
        DISABLED,
        /** Persistence was requested, but validation, connection, or migration setup failed. */
        UNAVAILABLE,
        /** The private pool and migrated schema were created successfully at startup. */
        ACTIVE
    }

    /**
     * Exhaustive outcomes from {@link #store(CalibrationFeedbackReport)}.
     * Created and Replayed include the stable server identity and original receipt time. Conflict
     * means the client ID already belongs to a different hash. CapacityRefused applies only to a new
     * client ID. Disabled and Unavailable keep optional-storage state out of exception handling.
     */
    public interface StoreResult {
        /** A new row was inserted. */
        record Created(UUID serverReportId, Instant submittedAt) implements StoreResult {
            public Created {
                Objects.requireNonNull(serverReportId, "serverReportId");
                Objects.requireNonNull(submittedAt, "submittedAt");
            }
        }

        /** The same client submission and hash had already been stored. */
        record Replayed(UUID serverReportId, Instant submittedAt) implements StoreResult {
            public Replayed {
                Objects.requireNonNull(serverReportId, "serverReportId");
                Objects.requireNonNull(submittedAt, "submittedAt");
            }
        }

        /** The client submission ID exists with a different idempotency hash. */
        record Conflict() implements StoreResult {
        }

        /** A new submission cannot fit within the configured bound. */
        record CapacityRefused() implements StoreResult {
        }

        /** Persistence was not enabled or required connection settings were incomplete. */
        record Disabled() implements StoreResult {
        }

        /** The active database call failed, or startup could not prepare persistence. */
        record Unavailable() implements StoreResult {
        }
    }

    /** Outcomes from the early client-submission lookup. */
    public interface LookupResult {
        /** The client ID exists; the hash copy lets the caller distinguish replay from conflict. */
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

        /** No row currently uses the client submission ID. */
        record NotFound() implements LookupResult {
        }

        /** Persistence was not enabled or required connection settings were incomplete. */
        record Disabled() implements LookupResult {
        }

        /** Lookup could not reach active storage, or startup setup failed. */
        record Unavailable() implements LookupResult {
        }
    }

    /** Operational state exposed without leaking individual feedback data. */
    public interface RepositoryStatus {
        /** A successful count snapshot and its derived capacity state. */
        record Available(CapacityState state, int used, int capacity, int remaining) implements RepositoryStatus {
            public Available {
                Objects.requireNonNull(state, "state");
                if (capacity <= 0 || used < 0 || remaining != Math.max(0, capacity - used)) {
                    throw new IllegalArgumentException("Capacity counts are inconsistent.");
                }
            }

            /** Classifies the count as near at 90 percent, or full at the configured bound. */
            public static Available from(int used, int capacity) {
                int nearAt = (capacity * 9 + 9) / 10;
                CapacityState state = used >= capacity
                        ? CapacityState.FULL
                        : used >= nearAt ? CapacityState.NEAR : CapacityState.NORMAL;
                return new Available(state, used, capacity, Math.max(0, capacity - used));
            }
        }

        /** Persistence was not enabled or required connection settings were incomplete. */
        record Disabled() implements RepositoryStatus {
        }

        /** The current count could not be read, or startup setup failed. */
        record Unavailable() implements RepositoryStatus {
        }
    }

    /** Aggregate capacity classification used for status and warning transitions. */
    public enum CapacityState {
        NORMAL,
        NEAR,
        FULL
    }

    /** Outcomes from deleting one server-assigned report ID. */
    public interface DeleteResult {
        /** The row and its contribution to the counter were removed. */
        record Deleted() implements DeleteResult {
        }

        /** No row currently has the requested server report ID. */
        record NotFound() implements DeleteResult {
        }

        /** Persistence was not enabled or required connection settings were incomplete. */
        record Disabled() implements DeleteResult {
        }

        /** Deletion could not reach active storage, or startup setup failed. */
        record Unavailable() implements DeleteResult {
        }
    }

    /** Internal projection used only for idempotency decisions. */
    private record StoredSubmission(UUID serverReportId, byte[] idempotencyHash, Instant submittedAt) {
    }

    /** Carries both the caller result and capacity transition out of the transaction. */
    private record StoreExecution(
            StoreResult result,
            CapacityState previousState,
            RepositoryStatus.Available currentStatus
    ) {
    }
}
