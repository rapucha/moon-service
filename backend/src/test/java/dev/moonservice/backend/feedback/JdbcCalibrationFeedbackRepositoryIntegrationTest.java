package dev.moonservice.backend.feedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class JdbcCalibrationFeedbackRepositoryIntegrationTest {
    private static final int TEST_CAPACITY = 10;

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18.4-trixie"))
            .withDatabaseName("feedback")
            .withUsername("feedback")
            .withPassword("test-only-feedback");

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    @Test
    @Order(1)
    void migratesAndStoresNormalizedReportData() throws Exception {
        contextRunner(null).run(context -> {
            assertThat(context).hasSingleBean(CalibrationFeedbackRepository.class);
            CalibrationFeedbackRepository repository = context.getBean(CalibrationFeedbackRepository.class);
            assertAvailable(repository, CalibrationFeedbackRepository.CapacityState.NORMAL, 0, 2_000);

            CalibrationFeedbackReport review = reviewReport(1, clientUuid(1), (byte) 1);
            CalibrationFeedbackReport observation = pastObservation(2, clientUuid(2), (byte) 2);
            CalibrationFeedbackRepository.StoreResult.Created reviewResult =
                    assertCreated(repository.store(review));
            CalibrationFeedbackRepository.StoreResult.Created observationResult =
                    assertCreated(repository.store(observation));

            assertThat(reviewResult.serverReportId().version()).isEqualTo(4);
            assertThat(reviewResult.serverReportId().variant()).isEqualTo(2);
            assertThat(reviewResult.submittedAt()).isEqualTo(review.timing().resolvedAt());
            assertThat(observationResult.serverReportId()).isNotEqualTo(reviewResult.serverReportId());
            assertThat(observationResult.submittedAt()).isEqualTo(observation.submittedAt());
            assertThat(observation.submittedAt().getNano()).isEqualTo(123_456_000);
            assertThat(repository.store(observation))
                    .isEqualTo(new CalibrationFeedbackRepository.StoreResult.Replayed(
                            observationResult.serverReportId(), observationResult.submittedAt()));
            CalibrationFeedbackRepository.LookupResult.Found found =
                    (CalibrationFeedbackRepository.LookupResult.Found)
                            repository.findByClientSubmissionId(review.clientSubmissionId());
            assertThat(found.serverReportId()).isEqualTo(reviewResult.serverReportId());
            assertThat(found.submittedAt()).isEqualTo(review.submittedAt());
            assertThat(found.idempotencyHash()).isEqualTo(review.idempotencyHash());
            assertAvailable(repository, CalibrationFeedbackRepository.CapacityState.NORMAL, 2, 2_000);

            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                assertThat(singleInt(statement, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(1);
                assertThat(singleInt(statement, "SELECT count(*) FROM calibration_feedback_report")).isEqualTo(2);
                try (ResultSet row = statement.executeQuery("""
                        SELECT report_mode, timing_kind, entered_local_datetime,
                               corrected_local_datetime, timing_timezone, utc_offset_seconds,
                               timing_source, timing_confidence, location_id, location_display_name,
                               latitude, longitude, elevation_meters, location_timezone, country_code,
                               overall_rating, moon_rating, ambient_light_rating, weather_rating,
                               horizon_rating, notes, recommendation_snapshot::text,
                               astronomy_snapshot::text, application_revision, idempotency_hash
                        FROM calibration_feedback_report
                        WHERE client_submission_id = '00000000-0000-4000-8000-000000000001'
                        """)) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("report_mode")).isEqualTo("recommendation_review");
                    assertThat(row.getString("timing_kind")).isEqualTo("now");
                    assertThat(row.getObject("entered_local_datetime")).isNull();
                    assertThat(row.getObject("corrected_local_datetime")).isNull();
                    assertThat(row.getString("timing_timezone")).isEqualTo("Europe/Prague");
                    assertThat(row.getInt("utc_offset_seconds")).isEqualTo(7_200);
                    assertThat(row.getString("timing_source")).isEqualTo("server_receipt");
                    assertThat(row.getString("timing_confidence")).isEqualTo("exact");
                    assertThat(row.getString("location_id")).isEqualTo("prague-cz");
                    assertThat(row.getString("location_display_name")).isEqualTo("Prague, Czechia");
                    assertThat(row.getBigDecimal("latitude")).isEqualByComparingTo("50.08804");
                    assertThat(row.getBigDecimal("longitude")).isEqualByComparingTo("14.42076");
                    assertThat(row.getInt("elevation_meters")).isEqualTo(202);
                    assertThat(row.getString("location_timezone")).isEqualTo("Europe/Prague");
                    assertThat(row.getString("country_code")).isEqualTo("CZ");
                    assertThat(row.getString("overall_rating")).isEqualTo("positive");
                    assertThat(row.getString("moon_rating")).isEqualTo("clear");
                    assertThat(row.getString("ambient_light_rating")).isEqualTo("sufficient");
                    assertThat(row.getString("weather_rating")).isEqualTo("matched");
                    assertThat(row.getString("horizon_rating")).isEqualTo("none");
                    assertThat(row.getString("notes")).isEqualTo("Normalized review note 1");
                    assertThat(row.getString("recommendation_snapshot"))
                            .isEqualTo("{\"id\": \"opportunity-1\", \"score\": 82}");
                    assertThat(row.getString("astronomy_snapshot"))
                            .isEqualTo("{\"light\": \"civil_twilight\", \"moonAltitude\": 4.2}");
                    assertThat(row.getString("application_revision")).isEqualTo("test-revision");
                    assertThat(row.getBytes("idempotency_hash")).isEqualTo(review.idempotencyHash());
                }
                try (ResultSet past = statement.executeQuery("""
                        SELECT timing_kind, entered_local_datetime, corrected_local_datetime,
                               resolved_local_datetime, timing_source, timing_confidence,
                               recommendation_snapshot, weather_rating
                        FROM calibration_feedback_report
                        WHERE client_submission_id = '00000000-0000-4000-8000-000000000002'
                        """)) {
                    assertThat(past.next()).isTrue();
                    assertThat(past.getString("timing_kind")).isEqualTo("past");
                    assertThat(past.getObject("entered_local_datetime", LocalDateTime.class))
                            .isEqualTo(LocalDateTime.parse("2026-01-15T19:25:00"));
                    assertThat(past.getObject("corrected_local_datetime", LocalDateTime.class))
                            .isEqualTo(LocalDateTime.parse("2026-01-15T19:30:00"));
                    assertThat(past.getObject("resolved_local_datetime", LocalDateTime.class))
                            .isEqualTo(LocalDateTime.parse("2026-01-15T19:30:00"));
                    assertThat(past.getString("timing_source")).isEqualTo("camera_metadata");
                    assertThat(past.getString("timing_confidence")).isEqualTo("within_5_minutes");
                    assertThat(past.getObject("recommendation_snapshot")).isNull();
                    assertThat(past.getString("weather_rating")).isEqualTo("not_compared");
                }
                assertPrivateColumnInventory(statement);
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
        });
    }

    @Test
    @Order(2)
    void handlesReplayConflictCapacityAndDeletionInRepositoryOrder() {
        contextRunner(2).run(context -> {
            CalibrationFeedbackRepository repository = context.getBean(CalibrationFeedbackRepository.class);
            CalibrationFeedbackReport first = reviewReport(1, clientUuid(1), (byte) 1);
            CalibrationFeedbackRepository.StoreResult.Created created = assertCreated(repository.store(first));

            assertThat(repository.store(first))
                    .isEqualTo(new CalibrationFeedbackRepository.StoreResult.Replayed(
                            created.serverReportId(), created.submittedAt()));
            assertThat(repository.store(reviewReport(11, first.clientSubmissionId(), (byte) 11)))
                    .isInstanceOf(CalibrationFeedbackRepository.StoreResult.Conflict.class);
            assertCreated(repository.store(reviewReport(2, clientUuid(2), (byte) 2)));
            assertAvailable(repository, CalibrationFeedbackRepository.CapacityState.FULL, 2, 2);
            assertThat(repository.store(reviewReport(3, clientUuid(3), (byte) 3)))
                    .isInstanceOf(CalibrationFeedbackRepository.StoreResult.CapacityRefused.class);
            assertThat(repository.store(first))
                    .isInstanceOf(CalibrationFeedbackRepository.StoreResult.Replayed.class);

            assertThat(repository.deleteByServerReportId(created.serverReportId()))
                    .isInstanceOf(CalibrationFeedbackRepository.DeleteResult.Deleted.class);
            assertThat(repository.deleteByServerReportId(created.serverReportId()))
                    .isInstanceOf(CalibrationFeedbackRepository.DeleteResult.NotFound.class);
            assertAvailable(repository, CalibrationFeedbackRepository.CapacityState.NORMAL, 1, 2);
        });
    }

    @Test
    @Order(3)
    void emitsAggregateOnlyNearFullTransitionAndStartupWarnings(CapturedOutput output) {
        UUID privateMarker = clientUuid(99);
        contextRunner(TEST_CAPACITY).run(context -> {
            CalibrationFeedbackRepository repository = context.getBean(CalibrationFeedbackRepository.class);
            for (int index = 1; index <= 8; index++) {
                assertCreated(repository.store(reviewReport(index, clientUuid(index), (byte) index)));
            }
            assertThat(output).doesNotContain("state=near").doesNotContain("state=full");
            assertCreated(repository.store(reviewReport(9, privateMarker, (byte) 9)));
            assertCreated(repository.store(reviewReport(10, clientUuid(10), (byte) 10)));
        });
        contextRunner(TEST_CAPACITY).run(context -> assertAvailable(
                context.getBean(CalibrationFeedbackRepository.class),
                CalibrationFeedbackRepository.CapacityState.FULL,
                10,
                TEST_CAPACITY));

        assertThat(output)
                .contains("event=transition state=near used=9 capacity=10 remaining=1")
                .contains("event=transition state=full used=10 capacity=10 remaining=0")
                .contains("event=startup state=full used=10 capacity=10 remaining=0")
                .doesNotContain(privateMarker.toString())
                .doesNotContain("Normalized review note 9")
                .doesNotContain("prague-cz");
    }

    @Test
    @Order(4)
    void enforcesCapacityAcrossConcurrentWriters() {
        contextRunner(TEST_CAPACITY).run(context -> {
            CalibrationFeedbackRepository repository = context.getBean(CalibrationFeedbackRepository.class);
            CountDownLatch ready = new CountDownLatch(24);
            CountDownLatch start = new CountDownLatch(1);
            List<CalibrationFeedbackRepository.StoreResult> results = new ArrayList<>();
            try (var executor = Executors.newFixedThreadPool(24)) {
                List<java.util.concurrent.Future<CalibrationFeedbackRepository.StoreResult>> futures = new ArrayList<>();
                for (int index = 1; index <= 24; index++) {
                    int reportIndex = index;
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return repository.store(reviewReport(
                                reportIndex, clientUuid(reportIndex), (byte) reportIndex));
                    }));
                }
                try {
                    ready.await();
                    start.countDown();
                    for (var future : futures) {
                        results.add(future.get());
                    }
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }

            assertThat(results.stream()
                    .filter(CalibrationFeedbackRepository.StoreResult.Created.class::isInstance))
                    .hasSize(TEST_CAPACITY);
            assertThat(results.stream()
                    .filter(CalibrationFeedbackRepository.StoreResult.CapacityRefused.class::isInstance))
                    .hasSize(14);
            assertAvailable(repository, CalibrationFeedbackRepository.CapacityState.FULL, 10, TEST_CAPACITY);
        });
    }

    @Test
    @Order(99)
    void mapsDatabaseLossToUnavailableRepositoryOutcomes() {
        contextRunner(TEST_CAPACITY).run(context -> {
            CalibrationFeedbackRepository repository = context.getBean(CalibrationFeedbackRepository.class);
            CalibrationFeedbackReport report = reviewReport(1, clientUuid(1), (byte) 1);
            CalibrationFeedbackRepository.StoreResult.Created created = assertCreated(repository.store(report));
            POSTGRES.stop();

            assertThat(repository.status())
                    .isInstanceOf(CalibrationFeedbackRepository.RepositoryStatus.Unavailable.class);
            assertThat(repository.findByClientSubmissionId(report.clientSubmissionId()))
                    .isInstanceOf(CalibrationFeedbackRepository.LookupResult.Unavailable.class);
            assertThat(repository.store(reviewReport(2, clientUuid(2), (byte) 2)))
                    .isInstanceOf(CalibrationFeedbackRepository.StoreResult.Unavailable.class);
            assertThat(repository.deleteByServerReportId(created.serverReportId()))
                    .isInstanceOf(CalibrationFeedbackRepository.DeleteResult.Unavailable.class);
        });
    }

    private ApplicationContextRunner contextRunner(Integer capacity) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(FeedbackPersistenceConfiguration.class)
                .withPropertyValues(
                        "moon.feedback.persistence.enabled=true",
                        "moon.feedback.persistence.jdbc-url=" + POSTGRES.getJdbcUrl(),
                        "moon.feedback.persistence.username=" + POSTGRES.getUsername(),
                        "moon.feedback.persistence.password=" + POSTGRES.getPassword());
        return capacity == null
                ? runner
                : runner.withPropertyValues("moon.feedback.persistence.capacity=" + capacity);
    }

    private static CalibrationFeedbackReport reviewReport(int index, UUID clientId, byte hashByte) {
        Instant at = Instant.parse("2026-07-19T12:00:00Z").plusSeconds(index);
        ZoneId timezone = ZoneId.of("Europe/Prague");
        LocalDateTime local = LocalDateTime.ofInstant(at, timezone);
        return report(
                clientId,
                hashByte,
                CalibrationFeedbackReport.ReportMode.RECOMMENDATION_REVIEW,
                new CalibrationFeedbackReport.NormalizedTiming(
                        CalibrationFeedbackReport.TimingKind.NOW,
                        null,
                        null,
                        local,
                        timezone,
                        ZoneOffset.ofHours(2),
                        CalibrationFeedbackReport.TimingSource.SERVER_RECEIPT,
                        CalibrationFeedbackReport.TimingConfidence.EXACT,
                        at),
                new CalibrationFeedbackReport.Ratings(
                        CalibrationFeedbackReport.OverallRating.POSITIVE,
                        CalibrationFeedbackReport.MoonRating.CLEAR,
                        CalibrationFeedbackReport.AmbientLightRating.SUFFICIENT,
                        CalibrationFeedbackReport.WeatherRating.MATCHED,
                        CalibrationFeedbackReport.HorizonRating.NONE),
                "Normalized review note " + index,
                "{\"id\":\"opportunity-1\",\"score\":82}",
                at);
    }

    private static CalibrationFeedbackReport pastObservation(int index, UUID clientId, byte hashByte) {
        Instant at = Instant.parse("2026-01-15T18:30:00Z");
        ZoneId timezone = ZoneId.of("Europe/Prague");
        return report(
                clientId,
                hashByte,
                CalibrationFeedbackReport.ReportMode.OBSERVATION,
                new CalibrationFeedbackReport.NormalizedTiming(
                        CalibrationFeedbackReport.TimingKind.PAST,
                        LocalDateTime.parse("2026-01-15T19:25:00"),
                        LocalDateTime.parse("2026-01-15T19:30:00"),
                        LocalDateTime.parse("2026-01-15T19:30:00"),
                        timezone,
                        ZoneOffset.ofHours(1),
                        CalibrationFeedbackReport.TimingSource.CAMERA_METADATA,
                        CalibrationFeedbackReport.TimingConfidence.WITHIN_5_MINUTES,
                        at),
                new CalibrationFeedbackReport.Ratings(
                        CalibrationFeedbackReport.OverallRating.MARGINAL,
                        CalibrationFeedbackReport.MoonRating.PARTIAL,
                        CalibrationFeedbackReport.AmbientLightRating.MARGINAL,
                        CalibrationFeedbackReport.WeatherRating.NOT_COMPARED,
                        CalibrationFeedbackReport.HorizonRating.MINOR),
                "Normalized observation note " + index,
                null,
                at.plusSeconds(index).plusNanos(123_456_789));
    }

    private static CalibrationFeedbackReport report(
            UUID clientId,
            byte hashByte,
            CalibrationFeedbackReport.ReportMode mode,
            CalibrationFeedbackReport.NormalizedTiming timing,
            CalibrationFeedbackReport.Ratings ratings,
            String notes,
            String recommendationSnapshot,
            Instant submittedAt
    ) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, hashByte);
        return new CalibrationFeedbackReport(
                1,
                clientId,
                mode,
                timing,
                new CalibrationFeedbackReport.CanonicalLocation(
                        "prague-cz",
                        "Prague, Czechia",
                        new BigDecimal("50.08804"),
                        new BigDecimal("14.42076"),
                        202,
                        ZoneId.of("Europe/Prague"),
                        "CZ"),
                ratings,
                notes,
                recommendationSnapshot,
                "{\"moonAltitude\":4.2,\"light\":\"civil_twilight\"}",
                "test-revision",
                hash,
                submittedAt);
    }

    private static UUID clientUuid(int index) {
        return UUID.fromString("00000000-0000-4000-8000-" + "%012x".formatted(index));
    }

    private static CalibrationFeedbackRepository.StoreResult.Created assertCreated(
            CalibrationFeedbackRepository.StoreResult result
    ) {
        assertThat(result).isInstanceOf(CalibrationFeedbackRepository.StoreResult.Created.class);
        return (CalibrationFeedbackRepository.StoreResult.Created) result;
    }

    private static void assertAvailable(
            CalibrationFeedbackRepository repository,
            CalibrationFeedbackRepository.CapacityState state,
            int used,
            int capacity
    ) {
        assertThat(repository.status()).isEqualTo(new CalibrationFeedbackRepository.RepositoryStatus.Available(
                state, used, capacity, Math.max(0, capacity - used)));
    }

    private static void assertPrivateColumnInventory(Statement statement) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'calibration_feedback_report'
                ORDER BY ordinal_position
                """)) {
            while (resultSet.next()) {
                columns.add(resultSet.getString(1));
            }
        }
        assertThat(columns)
                .doesNotContain(
                        "request_body", "ip_address", "forwarded", "forwarded_identity",
                        "user_agent", "visitor_id", "account_id")
                .contains(
                        "server_report_id", "client_submission_id", "idempotency_hash",
                        "location_id", "notes", "recommendation_snapshot", "astronomy_snapshot");
    }

    private static int singleInt(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
