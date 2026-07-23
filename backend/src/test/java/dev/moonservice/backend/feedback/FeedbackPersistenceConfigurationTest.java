package dev.moonservice.backend.feedback;

import com.zaxxer.hikari.HikariConfig;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.openmeteo.TestOpenMeteoLocationResolver;
import dev.moonservice.backend.weather.TestWeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "moon.location.resolver=open-meteo",
                "moon.weather.provider=open-meteo",
                "moon.feedback.enabled=true",
                "moon.feedback.persistence.enabled=true",
                "moon.feedback.persistence.jdbc-url="
                        + "jdbc:postgresql://127.0.0.1:1/feedback_unavailable?connectTimeout=1&socketTimeout=1",
                "moon.feedback.persistence.username=feedback-unavailable-user",
                "moon.feedback.persistence.password=feedback-unavailable-secret",
                "moon.feedback.persistence.capacity=2000"
        })
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
@Tag("functional")
class FeedbackPersistenceConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FeedbackPersistenceConfiguration.class);

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private CalibrationFeedbackRepository repository;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @TestConfiguration
    static class ProviderTestConfiguration {
        @Bean
        @Primary
        LocationResolver testLocationResolver() {
            return new TestOpenMeteoLocationResolver();
        }

        @Bean
        @Primary
        WeatherForecastProvider testWeatherForecastProvider() {
            return new TestWeatherForecastProvider();
        }
    }

    @Test
    void defaultsToDisabledWithoutCreatingDatabaseInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CalibrationFeedbackRepository.class);
            assertThat(context.getBean(CalibrationFeedbackRepository.class).status())
                    .isInstanceOf(CalibrationFeedbackRepository.RepositoryStatus.Disabled.class);
            assertThat(context).doesNotHaveBean(DataSource.class);
            assertThat(context).doesNotHaveBean(Flyway.class);
        });
    }

    @Test
    void incompleteOptInRemainsDisabled() {
        contextRunner
                .withPropertyValues(
                        "moon.feedback.persistence.enabled=true",
                        "moon.feedback.persistence.jdbc-url=jdbc:postgresql://database/feedback")
                .run(context -> assertThat(context.getBean(CalibrationFeedbackRepository.class).status())
                        .isInstanceOf(CalibrationFeedbackRepository.RepositoryStatus.Disabled.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number", "2001", " "})
    void invalidExplicitCapacityIsUnavailableWithoutOpeningDatabase(String capacity) {
        contextRunner
                .withPropertyValues(
                        "moon.feedback.persistence.enabled=true",
                        "moon.feedback.persistence.jdbc-url=jdbc:postgresql://database/feedback",
                        "moon.feedback.persistence.username=feedback",
                        "moon.feedback.persistence.password=test-only",
                        "moon.feedback.persistence.capacity=" + capacity)
                .run(context -> assertThat(context.getBean(CalibrationFeedbackRepository.class).status())
                        .isInstanceOf(CalibrationFeedbackRepository.RepositoryStatus.Unavailable.class));
    }

    @Test
    void nonPostgresqlUrlIsUnavailableWithoutOpeningDatabase() {
        contextRunner
                .withPropertyValues(
                        "moon.feedback.persistence.enabled=true",
                        "moon.feedback.persistence.jdbc-url=jdbc:h2:mem:feedback",
                        "moon.feedback.persistence.username=feedback",
                        "moon.feedback.persistence.password=test-only")
                .run(context -> assertThat(context.getBean(CalibrationFeedbackRepository.class).status())
                        .isInstanceOf(CalibrationFeedbackRepository.RepositoryStatus.Unavailable.class));
    }

    @Test
    void migrationAndActivePoolsUseSeparateConnectionDeadlines() {
        FeedbackPersistenceConfiguration.PersistenceProperties properties =
                new FeedbackPersistenceConfiguration.PersistenceProperties();
        properties.setJdbcUrl("jdbc:postgresql://database/feedback");
        properties.setUsername("feedback");
        properties.setPassword("test-only");

        HikariConfig migration = ReflectionTestUtils.invokeMethod(
                FeedbackPersistenceConfiguration.class, "migrationHikariConfig", properties);
        HikariConfig active = ReflectionTestUtils.invokeMethod(
                FeedbackPersistenceConfiguration.class, "activeHikariConfig", properties);

        assertThat(migration).isNotNull();
        assertThat(migration.getPoolName()).isEqualTo("moon-feedback-migration");
        assertThat(migration.getConnectionTimeout()).isEqualTo(30_000);
        assertThat(migration.getValidationTimeout()).isEqualTo(2_000);
        assertThat(migration.getDataSourceProperties())
                .containsEntry("connectTimeout", "5")
                .containsEntry("socketTimeout", "10");
        assertThat(active).isNotNull();
        assertThat(active.getPoolName()).isEqualTo("moon-feedback");
        assertThat(active.getConnectionTimeout()).isEqualTo(10_000);
        assertThat(active.getValidationTimeout()).isEqualTo(2_000);
        assertThat(active.getDataSourceProperties())
                .containsEntry("connectTimeout", "5")
                .containsEntry("socketTimeout", "10");
    }

    @Test
    void databaseOutageDoesNotAffectExistingTrafficAndFeedbackStaysUnavailable(CapturedOutput output) {
        assertThat(repository.status())
                .isInstanceOf(CalibrationFeedbackRepository.RepositoryStatus.Unavailable.class);
        assertThat(applicationContext.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(Flyway.class)).isEmpty();

        webTestClient.get()
                .uri("/healthz")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok");
        webTestClient.get()
                .uri("/readyz")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok");
        webTestClient.get()
                .uri("/api/opportunities?q=Praha")
                .exchange()
                .expectStatus().isOk();
        webTestClient.get()
                .uri("/api/calibration-feedback/v1/capability")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.schemaVersion").isEqualTo(1)
                .jsonPath("$.featureState").isEqualTo("enabled")
                .jsonPath("$.submissionAvailability").isEqualTo("unavailable")
                .jsonPath("$.database").doesNotExist()
                .jsonPath("$.capacity").doesNotExist();
        webTestClient.post()
                .uri("/api/calibration-feedback/v1/submissions")
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {
                          "schemaVersion": 1,
                          "clientSubmissionId": "7d444840-9dc0-4f68-b705-06585fa7f974",
                          "locationId": "private-location-marker",
                          "opportunityId": "private-opportunity-marker",
                          "notes": "private-note-marker"
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("feedback_unavailable")
                .jsonPath("$.error.message").isEqualTo("Calibration feedback is unavailable.");

        assertThat(output)
                .doesNotContain("feedback-unavailable-secret")
                .doesNotContain("feedback-unavailable-user")
                .doesNotContain("feedback_unavailable?connectTimeout")
                .doesNotContain("private-location-marker")
                .doesNotContain("private-opportunity-marker")
                .doesNotContain("private-note-marker")
                .doesNotContain("7d444840-9dc0-4f68-b705-06585fa7f974");
    }
}
