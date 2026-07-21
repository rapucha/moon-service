package dev.moonservice.backend.feedback;

import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.openmeteo.TestOpenMeteoLocationResolver;
import dev.moonservice.backend.weather.TestWeatherForecastProvider;
import dev.moonservice.backend.weather.WeatherForecastProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "moon.location.resolver=open-meteo",
                "moon.weather.provider=open-meteo",
                "moon.admin.token=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "moon.hosted-alpha.enabled=true",
                "moon.feedback.enabled=true",
                "moon.build.revision=provider-admission-test",
                "moon.resource-limits.whole-site-capacity=1",
                "moon.resource-limits.whole-site-refill-interval=1h",
                "moon.resource-limits.provider-lookup-capacity=1",
                "moon.resource-limits.provider-lookup-refill-interval=1m",
                "moon.resource-limits.opportunity-concurrency=2"
        })
@AutoConfigureWebTestClient
@Tag("functional")
class HostedAlphaProviderAdmissionFunctionalTest {
    private static final Instant NOW = Instant.parse("2026-07-20T10:15:30.123456Z");
    private static final UUID SERVER_REPORT_ID =
            UUID.fromString("2c1b827c-981f-4d4f-98d5-89bbd62792dc");

    @Autowired
    private WebTestClient webTestClient;

    @TestConfiguration
    static class ProviderAdmissionTestConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

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

        @Bean
        @Primary
        CalibrationFeedbackRepository availableFeedbackRepository() {
            CalibrationFeedbackRepository repository = mock(CalibrationFeedbackRepository.class);
            when(repository.findByClientSubmissionId(any()))
                    .thenReturn(new CalibrationFeedbackRepository.LookupResult.NotFound());
            when(repository.status()).thenReturn(new CalibrationFeedbackRepository.RepositoryStatus.Available(
                    CalibrationFeedbackRepository.CapacityState.NORMAL, 0, 2_000, 2_000));
            when(repository.store(any())).thenAnswer(invocation -> {
                CalibrationFeedbackReport report = invocation.getArgument(0);
                return new CalibrationFeedbackRepository.StoreResult.Created(
                        SERVER_REPORT_ID, report.submittedAt());
            });
            return repository;
        }
    }

    @Test
    void feedbackBypassesWholeSiteAdmissionAndConsumesSharedProviderAdmission() {
        webTestClient.post()
                .uri("/api/calibration-feedback/v1/submissions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemaVersion":1,
                         "clientSubmissionId":"f47ac10b-58cc-4372-a567-0e02b2c3d479",
                         "locationId":"prague-cz",
                         "opportunityId":"opportunity-1",
                         "ambientLight":"good"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.status").isEqualTo("created");

        webTestClient.get()
                .uri("/api/opportunities?q=Prague")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "60")
                .expectBody()
                .jsonPath("$.status").isEqualTo("rate_limited")
                .jsonPath("$.retryAfterSeconds").isEqualTo(60);
    }
}
