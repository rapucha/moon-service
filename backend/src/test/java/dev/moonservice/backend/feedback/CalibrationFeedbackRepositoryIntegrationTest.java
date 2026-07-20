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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class CalibrationFeedbackRepositoryIntegrationTest {
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
    void migratesAndStoresOptionalCurrentEvidence() throws Exception {
        contextRunner(null).run(context -> {
            CalibrationFeedbackRepository repository = context.getBean(CalibrationFeedbackRepository.class);
            assertAvailable(repository, CalibrationFeedbackRepository.CapacityState.NORMAL, 0, 2_000);

            CalibrationFeedbackReport mixed = report(
                    1,
                    clientUuid(1),
                    (byte) 1,
                    CalibrationFeedbackReport.AmbientLight.TOO_BRIGHT,
                    CalibrationFeedbackReport.CrescentVisibility.TOO_SMALL_TO_SEE,
                    "Světlo je příliš silné. القمر صغير 🌙 月亮");
            CalibrationFeedbackReport notesOnly = report(2, clientUuid(2), (byte) 2, null, null, "🌙");
            CalibrationFeedbackReport structuredOnly = report(
                    3,
                    clientUuid(3),
                    (byte) 3,
                    CalibrationFeedbackReport.AmbientLight.TOO_DARK,
                    CalibrationFeedbackReport.CrescentVisibility.VISIBLE,
                    null);

            CalibrationFeedbackRepository.StoreResult.Created mixedResult =
                    assertCreated(repository.store(mixed));
            assertCreated(repository.store(notesOnly));
            assertCreated(repository.store(structuredOnly));

            assertThat(mixedResult.serverReportId().version()).isEqualTo(4);
            assertThat(mixedResult.serverReportId().variant()).isEqualTo(2);
            assertThat(mixedResult.submittedAt()).isEqualTo(mixed.submittedAt());
            assertThat(mixed.submittedAt().getNano()).isEqualTo(123_456_000);
            assertAvailable(repository, CalibrationFeedbackRepository.CapacityState.NORMAL, 3, 2_000);

            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                assertThat(singleInt(statement, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(1);
                assertThat(singleInt(statement, "SELECT count(*) FROM calibration_feedback_report")).isEqualTo(3);
                try (ResultSet row = statement.executeQuery("""
                        SELECT schema_version, opportunity_id, location_id, ambient_light, crescent_visibility, notes,
                               moon_altitude_degrees, moon_illumination_percent, sun_altitude_degrees, light_bucket,
                               application_revision, idempotency_hash, submitted_at
                        FROM calibration_feedback_report
                        WHERE client_submission_id = '00000000-0000-4000-8000-000000000001'
                        """)) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt("schema_version"))
                            .isEqualTo(mixed.schemaVersion())
                            .isEqualTo(CalibrationFeedbackReport.REPORT_SCHEMA_VERSION);
                    assertThat(row.getString("opportunity_id")).isEqualTo("opportunity-1");
                    assertThat(row.getString("location_id")).isEqualTo("moon-service-3067696");
                    assertThat(row.getString("ambient_light")).isEqualTo("TOO_BRIGHT");
                    assertThat(row.getString("crescent_visibility")).isEqualTo("TOO_SMALL_TO_SEE");
                    assertThat(row.getString("notes")).isEqualTo(mixed.notes());
                    assertThat(row.getDouble("moon_altitude_degrees")).isEqualTo(4.2);
                    assertThat(row.getDouble("moon_illumination_percent")).isEqualTo(3.1);
                    assertThat(row.getDouble("sun_altitude_degrees")).isEqualTo(-4.0);
                    assertThat(row.getString("light_bucket")).isEqualTo("CIVIL_TWILIGHT");
                    assertThat(row.getString("application_revision")).isEqualTo("test-revision");
                    assertThat(row.getBytes("idempotency_hash")).isEqualTo(mixed.idempotencyHash());
                    assertThat(row.getObject("submitted_at", OffsetDateTime.class).toInstant())
                            .isEqualTo(mixed.submittedAt());
                }
                try (ResultSet notes = statement.executeQuery("""
                        SELECT ambient_light, crescent_visibility, notes
                        FROM calibration_feedback_report
                        WHERE client_submission_id = '00000000-0000-4000-8000-000000000002'
                        """)) {
                    assertThat(notes.next()).isTrue();
                    assertThat(notes.getObject("ambient_light")).isNull();
                    assertThat(notes.getObject("crescent_visibility")).isNull();
                    assertThat(notes.getString("notes")).isEqualTo("🌙");
                }
                try (ResultSet structured = statement.executeQuery("""
                        SELECT ambient_light, crescent_visibility, notes
                        FROM calibration_feedback_report
                        WHERE client_submission_id = '00000000-0000-4000-8000-000000000003'
                        """)) {
                    assertThat(structured.next()).isTrue();
                    assertThat(structured.getString("ambient_light")).isEqualTo("TOO_DARK");
                    assertThat(structured.getString("crescent_visibility")).isEqualTo("VISIBLE");
                    assertThat(structured.getObject("notes")).isNull();
                }
                assertThatThrownBy(() -> statement.executeUpdate("""
                        UPDATE calibration_feedback_report
                        SET ambient_light = NULL, crescent_visibility = NULL, notes = NULL
                        WHERE client_submission_id = '00000000-0000-4000-8000-000000000003'
                        """))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate("""
                        UPDATE calibration_feedback_report
                        SET moon_altitude_degrees = 91.0
                        WHERE client_submission_id = '00000000-0000-4000-8000-000000000003'
                        """))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate("""
                        UPDATE calibration_feedback_report
                        SET light_bucket = 'civil_twilight'
                        WHERE client_submission_id = '00000000-0000-4000-8000-000000000003'
                        """))
                        .isInstanceOf(SQLException.class);
                assertPrivateColumnInventory(statement);
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
        });
    }

    @Test
    @Order(2)
    void storesUnicodeNotesAtCodePointBounds() {
        contextRunner(null).run(context -> {
            CalibrationFeedbackRepository repository = context.getBean(CalibrationFeedbackRepository.class);
            String emoji = "🌙";
            assertCreated(repository.store(report(1, clientUuid(1), (byte) 1, null, null, emoji)));
            assertCreated(repository.store(report(
                    2, clientUuid(2), (byte) 2, null, null, emoji.repeat(4_000))));

            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                assertThat(singleInt(statement, """
                        SELECT char_length(notes) FROM calibration_feedback_report
                        WHERE client_submission_id = '00000000-0000-4000-8000-000000000001'
                        """)).isEqualTo(1);
                assertThat(singleInt(statement, """
                        SELECT char_length(notes) FROM calibration_feedback_report
                        WHERE client_submission_id = '00000000-0000-4000-8000-000000000002'
                        """)).isEqualTo(4_000);
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
        });
    }

    @Test
    @Order(3)
    void rejectsMissingOrNonCanonicalEvidence() {
        assertThatThrownBy(() -> report(1, clientUuid(1), (byte) 1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one feedback field");
        assertThatThrownBy(() -> report(1, clientUuid(1), (byte) 1, null, null, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> report(1, clientUuid(1), (byte) 1, null, null, " \t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> report(1, clientUuid(1), (byte) 1, null, null, "\u00a0Moon"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> report(1, clientUuid(1), (byte) 1, null, null, "Moon\u202f"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> report(1, clientUuid(1), (byte) 1, null, null, "Cafe\u0301"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> report(1, clientUuid(1), (byte) 1, null, null, "🌙".repeat(4_001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> report(
                1, clientUuid(1), (byte) 1, null, null, String.valueOf(Character.highSurrogate(0x1f319))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unpaired surrogates");
        assertThatThrownBy(() -> report(
                1, clientUuid(1), (byte) 1, null, null, String.valueOf(Character.lowSurrogate(0x1f319))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unpaired surrogates");
        assertThatThrownBy(() -> report(1, clientUuid(1), (byte) 1, null, null, String.valueOf((char) 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("U+0000");
        assertThatThrownBy(() -> report(
                1,
                UUID.fromString("00000000-0000-1000-8000-000000000001"),
                (byte) 1,
                CalibrationFeedbackReport.AmbientLight.GOOD,
                null,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUIDv4");
        assertThatThrownBy(() -> new CalibrationFeedbackReport.AstronomyFacts(
                Double.NaN, 3.1, -4.0, CalibrationFeedbackReport.LightBucket.CIVIL_TWILIGHT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moonAltitudeDegrees");
        assertThatThrownBy(() -> new CalibrationFeedbackReport.AstronomyFacts(
                4.2, 100.1, -4.0, CalibrationFeedbackReport.LightBucket.CIVIL_TWILIGHT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moonIlluminationPercent");
        assertThatThrownBy(() -> new CalibrationFeedbackReport.AstronomyFacts(
                4.2, 3.1, Double.POSITIVE_INFINITY, CalibrationFeedbackReport.LightBucket.CIVIL_TWILIGHT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sunAltitudeDegrees");
        assertThatThrownBy(() -> new CalibrationFeedbackReport.AstronomyFacts(4.2, 3.1, -4.0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lightBucket");
    }

    @Test
    @Order(4)
    void handlesReplayConflictCapacityAndDeletionInRepositoryOrder() {
        contextRunner(2).run(context -> {
            CalibrationFeedbackRepository repository = context.getBean(CalibrationFeedbackRepository.class);
            CalibrationFeedbackReport first = structuredReport(1, clientUuid(1), (byte) 1);
            CalibrationFeedbackRepository.StoreResult.Created created = assertCreated(repository.store(first));

            assertThat(repository.store(first))
                    .isEqualTo(new CalibrationFeedbackRepository.StoreResult.Replayed(
                            created.serverReportId(), created.submittedAt()));
            assertThat(repository.store(structuredReport(11, first.clientSubmissionId(), (byte) 11)))
                    .isInstanceOf(CalibrationFeedbackRepository.StoreResult.Conflict.class);
            assertCreated(repository.store(structuredReport(2, clientUuid(2), (byte) 2)));
            assertAvailable(repository, CalibrationFeedbackRepository.CapacityState.FULL, 2, 2);
            assertThat(repository.store(structuredReport(3, clientUuid(3), (byte) 3)))
                    .isInstanceOf(CalibrationFeedbackRepository.StoreResult.CapacityRefused.class);
            assertThat(repository.store(first))
                    .isInstanceOf(CalibrationFeedbackRepository.StoreResult.Replayed.class);

            CalibrationFeedbackRepository.LookupResult.Found found =
                    (CalibrationFeedbackRepository.LookupResult.Found)
                            repository.findByClientSubmissionId(first.clientSubmissionId());
            assertThat(found.serverReportId()).isEqualTo(created.serverReportId());
            assertThat(found.submittedAt()).isEqualTo(first.submittedAt());
            assertThat(found.idempotencyHash()).isEqualTo(first.idempotencyHash());

            assertThat(repository.deleteByServerReportId(created.serverReportId()))
                    .isInstanceOf(CalibrationFeedbackRepository.DeleteResult.Deleted.class);
            assertThat(repository.deleteByServerReportId(created.serverReportId()))
                    .isInstanceOf(CalibrationFeedbackRepository.DeleteResult.NotFound.class);
            assertAvailable(repository, CalibrationFeedbackRepository.CapacityState.NORMAL, 1, 2);
        });
    }

    @Test
    @Order(5)
    void emitsAggregateOnlyNearFullTransitionAndStartupWarnings(CapturedOutput output) {
        UUID privateMarker = clientUuid(99);
        contextRunner(TEST_CAPACITY).run(context -> {
            CalibrationFeedbackRepository repository = context.getBean(CalibrationFeedbackRepository.class);
            for (int index = 1; index <= 8; index++) {
                assertCreated(repository.store(structuredReport(index, clientUuid(index), (byte) index)));
            }
            assertThat(output).doesNotContain("state=near").doesNotContain("state=full");
            assertCreated(repository.store(structuredReport(9, privateMarker, (byte) 9)));
            assertCreated(repository.store(structuredReport(10, clientUuid(10), (byte) 10)));
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
                .doesNotContain("opportunity-9")
                .doesNotContain("moon-service-3067696");
    }

    @Test
    @Order(6)
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
                        return repository.store(structuredReport(
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
            CalibrationFeedbackReport report = structuredReport(1, clientUuid(1), (byte) 1);
            CalibrationFeedbackRepository.StoreResult.Created created = assertCreated(repository.store(report));
            POSTGRES.stop();

            assertThat(repository.status())
                    .isInstanceOf(CalibrationFeedbackRepository.RepositoryStatus.Unavailable.class);
            assertThat(repository.findByClientSubmissionId(report.clientSubmissionId()))
                    .isInstanceOf(CalibrationFeedbackRepository.LookupResult.Unavailable.class);
            assertThat(repository.store(structuredReport(2, clientUuid(2), (byte) 2)))
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

    private static CalibrationFeedbackReport structuredReport(int index, UUID clientId, byte hashByte) {
        return report(
                index,
                clientId,
                hashByte,
                CalibrationFeedbackReport.AmbientLight.GOOD,
                null,
                null);
    }

    private static CalibrationFeedbackReport report(
            int index,
            UUID clientId,
            byte hashByte,
            CalibrationFeedbackReport.AmbientLight ambientLight,
            CalibrationFeedbackReport.CrescentVisibility crescentVisibility,
            String notes
    ) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, hashByte);
        return new CalibrationFeedbackReport(
                CalibrationFeedbackReport.REPORT_SCHEMA_VERSION,
                clientId,
                "opportunity-" + index,
                "moon-service-3067696",
                ambientLight,
                crescentVisibility,
                notes,
                new CalibrationFeedbackReport.AstronomyFacts(
                        4.2,
                        3.1,
                        -4.0,
                        CalibrationFeedbackReport.LightBucket.CIVIL_TWILIGHT),
                "test-revision",
                hash,
                Instant.parse("2026-07-19T12:00:00Z")
                        .plusSeconds(index)
                        .plusNanos(123_456_789));
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
        assertThat(columns).containsExactly(
                "server_report_id",
                "client_submission_id",
                "schema_version",
                "opportunity_id",
                "location_id",
                "ambient_light",
                "crescent_visibility",
                "notes",
                "moon_altitude_degrees",
                "moon_illumination_percent",
                "sun_altitude_degrees",
                "light_bucket",
                "application_revision",
                "idempotency_hash",
                "submitted_at");
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
