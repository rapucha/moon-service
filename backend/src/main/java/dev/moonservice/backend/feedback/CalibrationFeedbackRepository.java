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

public final class CalibrationFeedbackRepository implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalibrationFeedbackRepository.class);
    private static final Map<String, ?> NO_PARAMETERS = Map.of();
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

    private final Mode mode;
    private final HikariDataSource dataSource;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int capacity;

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

    static CalibrationFeedbackRepository disabled() {
        return new CalibrationFeedbackRepository(Mode.DISABLED);
    }

    static CalibrationFeedbackRepository unavailable() {
        return new CalibrationFeedbackRepository(Mode.UNAVAILABLE);
    }

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

    void warnIfNearOrFullAtStartup() {
        RepositoryStatus repositoryStatus = status();
        if (repositoryStatus instanceof RepositoryStatus.Available available
                && available.state() != CapacityState.NORMAL) {
            warnCapacity("startup", available);
        }
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private StoreExecution storeInTransaction(CalibrationFeedbackReport report) {
        int used = lockAndCountReports();
        RepositoryStatus.Available previousStatus = RepositoryStatus.Available.from(used, capacity);
        StoredSubmission existing = find(report.clientSubmissionId());
        if (existing != null) {
            StoreResult result = MessageDigest.isEqual(existing.idempotencyHash(), report.idempotencyHash())
                    ? new StoreResult.Replayed(existing.serverReportId(), existing.submittedAt())
                    : new StoreResult.Conflict();
            return new StoreExecution(result, previousStatus.state(), previousStatus);
        }
        if (used >= capacity) {
            return new StoreExecution(new StoreResult.CapacityRefused(), previousStatus.state(), previousStatus);
        }

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

    private int lockAndCountReports() {
        Integer count = jdbc.queryForObject(LOCK_COUNT_SQL, NO_PARAMETERS, Integer.class);
        if (count == null) {
            throw new DataAccessResourceFailureException("Feedback capacity row is missing.");
        }
        return count;
    }

    private int countReports() {
        Integer count = jdbc.queryForObject(COUNT_SQL, NO_PARAMETERS, Integer.class);
        if (count == null) {
            throw new DataAccessResourceFailureException("Feedback capacity row is missing.");
        }
        return count;
    }

    private StoredSubmission find(UUID clientSubmissionId) {
        return jdbc.query(FIND_SQL, Map.of("clientSubmissionId", clientSubmissionId), resultSet -> resultSet.next()
                ? new StoredSubmission(
                        resultSet.getObject("server_report_id", UUID.class),
                        resultSet.getBytes("idempotency_hash"),
                        resultSet.getObject("submitted_at", OffsetDateTime.class).toInstant())
                : null);
    }

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

    private static void warnCapacity(String event, RepositoryStatus.Available status) {
        LOGGER.warn(
                "feedback_storage_capacity event={} state={} used={} capacity={} remaining={}",
                event,
                status.state().name().toLowerCase(java.util.Locale.ROOT),
                status.used(),
                status.capacity(),
                status.remaining());
    }

    private enum Mode {
        DISABLED,
        UNAVAILABLE,
        ACTIVE
    }

    public interface StoreResult {
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

    public interface LookupResult {
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

    public interface RepositoryStatus {
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

    public enum CapacityState {
        NORMAL,
        NEAR,
        FULL
    }

    public interface DeleteResult {
        record Deleted() implements DeleteResult {
        }

        record NotFound() implements DeleteResult {
        }

        record Disabled() implements DeleteResult {
        }

        record Unavailable() implements DeleteResult {
        }
    }

    private record StoredSubmission(UUID serverReportId, byte[] idempotencyHash, Instant submittedAt) {
    }

    private record StoreExecution(
            StoreResult result,
            CapacityState previousState,
            RepositoryStatus.Available currentStatus
    ) {
    }
}
