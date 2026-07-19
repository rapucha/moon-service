package dev.moonservice.backend.feedback;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

final class JdbcCalibrationFeedbackRepository implements CalibrationFeedbackRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcCalibrationFeedbackRepository.class);
    private static final String COUNT_SQL = """
            SELECT report_count
            FROM calibration_feedback_capacity
            WHERE singleton = TRUE
            """;
    private static final String LOCK_COUNT_SQL = COUNT_SQL + " FOR UPDATE";
    private static final String FIND_SQL = """
            SELECT server_report_id, idempotency_hash, submitted_at
            FROM calibration_feedback_report
            WHERE client_submission_id = ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO calibration_feedback_report (
                server_report_id,
                client_submission_id,
                schema_version,
                opportunity_id,
                location_id,
                location_display_name,
                latitude,
                longitude,
                elevation_meters,
                location_timezone,
                country_code,
                ambient_light,
                crescent_visibility,
                notes,
                astronomy_snapshot,
                application_revision,
                idempotency_hash,
                submitted_at
            ) VALUES (
                :serverReportId,
                :clientSubmissionId,
                :schemaVersion,
                :opportunityId,
                :locationId,
                :locationDisplayName,
                :latitude,
                :longitude,
                :elevationMeters,
                :locationTimezone,
                :countryCode,
                :ambientLight,
                :crescentVisibility,
                :notes,
                CAST(:astronomySnapshot AS jsonb),
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
            WHERE server_report_id = ?
            """;
    private static final String DECREMENT_SQL = """
            UPDATE calibration_feedback_capacity
            SET report_count = report_count - 1
            WHERE singleton = TRUE AND report_count > 0
            """;

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TransactionTemplate transactions;
    private final int capacity;

    JdbcCalibrationFeedbackRepository(HikariDataSource dataSource, int capacity) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive.");
        }
        this.capacity = capacity;
        this.jdbc = new JdbcTemplate(dataSource);
        this.namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public StoreResult store(CalibrationFeedbackReport report) {
        Objects.requireNonNull(report, "report");
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

    @Override
    public LookupResult findByClientSubmissionId(UUID clientSubmissionId) {
        Objects.requireNonNull(clientSubmissionId, "clientSubmissionId");
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

    @Override
    public RepositoryStatus status() {
        try {
            return RepositoryStatus.Available.from(countReports(), capacity);
        } catch (DataAccessException exception) {
            return new RepositoryStatus.Unavailable();
        }
    }

    @Override
    public DeleteResult deleteByServerReportId(UUID serverReportId) {
        Objects.requireNonNull(serverReportId, "serverReportId");
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
        dataSource.close();
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
        int inserted = namedJdbc.update(INSERT_SQL, parameters(serverReportId, report));
        int incremented = jdbc.update(INCREMENT_SQL);
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
        int deleted = jdbc.update(DELETE_SQL, serverReportId);
        if (deleted == 0) {
            return new DeleteResult.NotFound();
        }
        if (deleted != 1 || jdbc.update(DECREMENT_SQL) != 1) {
            throw new DataAccessResourceFailureException("Feedback delete did not update expected rows.");
        }
        return new DeleteResult.Deleted();
    }

    private int lockAndCountReports() {
        Integer count = jdbc.queryForObject(LOCK_COUNT_SQL, Integer.class);
        if (count == null) {
            throw new DataAccessResourceFailureException("Feedback capacity row is missing.");
        }
        return count;
    }

    private int countReports() {
        Integer count = jdbc.queryForObject(COUNT_SQL, Integer.class);
        if (count == null) {
            throw new DataAccessResourceFailureException("Feedback capacity row is missing.");
        }
        return count;
    }

    private StoredSubmission find(UUID clientSubmissionId) {
        return jdbc.query(FIND_SQL, resultSet -> resultSet.next()
                ? new StoredSubmission(
                        resultSet.getObject("server_report_id", UUID.class),
                        resultSet.getBytes("idempotency_hash"),
                        resultSet.getObject("submitted_at", OffsetDateTime.class).toInstant())
                : null, clientSubmissionId);
    }

    private static MapSqlParameterSource parameters(UUID serverReportId, CalibrationFeedbackReport report) {
        CalibrationFeedbackReport.CanonicalLocation location = report.location();
        return new MapSqlParameterSource()
                .addValue("serverReportId", serverReportId)
                .addValue("clientSubmissionId", report.clientSubmissionId())
                .addValue("schemaVersion", report.schemaVersion())
                .addValue("opportunityId", report.opportunityId())
                .addValue("locationId", location.id())
                .addValue("locationDisplayName", location.displayName())
                .addValue("latitude", location.latitude())
                .addValue("longitude", location.longitude())
                .addValue("elevationMeters", location.elevationMeters())
                .addValue("locationTimezone", location.timezone().getId())
                .addValue("countryCode", location.countryCode())
                .addValue("ambientLight", wireValue(report.ambientLight()), Types.VARCHAR)
                .addValue("crescentVisibility", wireValue(report.crescentVisibility()), Types.VARCHAR)
                .addValue("notes", report.notes(), Types.VARCHAR)
                .addValue("astronomySnapshot", report.astronomySnapshot(), Types.VARCHAR)
                .addValue("applicationRevision", report.applicationRevision())
                .addValue("idempotencyHash", report.idempotencyHash())
                .addValue("submittedAt", utc(report.submittedAt()), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    private static String wireValue(CalibrationFeedbackReport.WireValue value) {
        return value == null ? null : value.wireValue();
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
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

    private record StoredSubmission(UUID serverReportId, byte[] idempotencyHash, java.time.Instant submittedAt) {
    }

    private record StoreExecution(
            StoreResult result,
            CapacityState previousState,
            RepositoryStatus.Available currentStatus
    ) {
    }
}
