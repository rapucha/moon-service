package dev.moonservice.backend.feedback;

import dev.moonservice.backend.config.MoonRuntimeProperties;
import dev.moonservice.backend.location.LocationProvider;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.ProviderLocationId;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.backend.web.HostedAlphaResourceLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CalibrationFeedbackServiceTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-20T20:21:22.123456789Z");
    private static final ResolvedLocation RESOLVED_LOCATION = new ResolvedLocation(
            "moon-service-3067696",
            new ProviderLocationId(LocationProvider.OPEN_METEO, "3067696"),
            "Prague, Czechia",
            50.0755,
            14.4378,
            235,
            ZoneId.of("Europe/Prague"),
            "CZ");
    private static final CalibrationFeedbackReport.AstronomyFacts FACTS =
            new CalibrationFeedbackReport.AstronomyFacts(
                    4.5, 21.25, -5.75, CalibrationFeedbackReport.LightBucket.CIVIL_TWILIGHT);

    private CalibrationFeedbackRepository repository;
    private LocationResolver resolver;
    private AtomicLong monotonicNanos;
    private BiFunction<ResolvedLocation, Instant, CalibrationFeedbackReport.AstronomyFacts> astronomy;
    private Function<Supplier<LocationResolution>, Optional<LocationResolution>> locationAdmission;

    @BeforeEach
    void setUp() {
        repository = mock(CalibrationFeedbackRepository.class);
        resolver = mock(LocationResolver.class);
        monotonicNanos = new AtomicLong();
        astronomy = (location, instant) -> FACTS;
        locationAdmission = operation -> Optional.ofNullable(operation.get());
        when(repository.findByClientSubmissionId(any()))
                .thenReturn(new CalibrationFeedbackRepository.LookupResult.NotFound());
        when(repository.status())
                .thenReturn(CalibrationFeedbackRepository.RepositoryStatus.Available.from(0, 2_000));
        when(resolver.resolveLocationId(any())).thenReturn(LocationResolution.resolved(RESOLVED_LOCATION));
        when(repository.store(any())).thenAnswer(invocation -> {
            CalibrationFeedbackReport report = invocation.getArgument(0);
            Instant submittedAt = report == null
                    ? RECEIVED_AT.truncatedTo(ChronoUnit.MICROS)
                    : report.submittedAt();
            return new CalibrationFeedbackRepository.StoreResult.Created(UUID.randomUUID(), submittedAt);
        });
    }

    @Test
    void mapsCapabilityWithoutLeakingRepositoryDetails() {
        CalibrationFeedbackService disabled = service(false);
        assertThat(disabled.capability()).isEqualTo(new CalibrationFeedbackService.Capability("disabled", "disabled"));
        verifyNoInteractions(repository, resolver);

        when(repository.status()).thenReturn(new CalibrationFeedbackRepository.RepositoryStatus.Disabled());
        assertThat(service(true).capability())
                .isEqualTo(new CalibrationFeedbackService.Capability("enabled", "disabled"));
        when(repository.status()).thenReturn(new CalibrationFeedbackRepository.RepositoryStatus.Unavailable());
        assertThat(service(true).capability())
                .isEqualTo(new CalibrationFeedbackService.Capability("enabled", "unavailable"));
        when(repository.status()).thenReturn(CalibrationFeedbackRepository.RepositoryStatus.Available.from(2_000, 2_000));
        assertThat(service(true).capability())
                .isEqualTo(new CalibrationFeedbackService.Capability("enabled", "unavailable"));
        when(repository.status()).thenReturn(CalibrationFeedbackRepository.RepositoryStatus.Available.from(1_900, 2_000));
        assertThat(service(true).capability())
                .isEqualTo(new CalibrationFeedbackService.Capability("enabled", "available"));
    }

    @Test
    void rejectsDisabledSubmissionBeforeRepositoryResolverOrTokenWork() {
        CalibrationFeedbackService.SubmissionResult result = service(false)
                .submit(submission((byte) 1), RECEIVED_AT, "test-revision");

        assertThat(result).isInstanceOf(CalibrationFeedbackService.Unavailable.class);
        verifyNoInteractions(repository, resolver);
    }

    @Test
    void handlesEarlyReplayAndConflictBeforeStatusResolverOrTokenWork() {
        CalibrationFeedbackSubmission submission = submission((byte) 1);
        UUID serverId = UUID.randomUUID();
        Instant originalTime = Instant.parse("2026-07-19T01:02:03.123456Z");
        when(repository.findByClientSubmissionId(submission.clientSubmissionId()))
                .thenReturn(new CalibrationFeedbackRepository.LookupResult.Found(
                        serverId, submission.idempotencyHash(), originalTime));

        assertThat(service(true).submit(submission, RECEIVED_AT, "test-revision"))
                .isEqualTo(new CalibrationFeedbackService.Replayed(
                        submission.clientSubmissionId(), serverId, originalTime));
        verify(repository, never()).status();
        verifyNoInteractions(resolver);

        byte[] differentHash = submission.idempotencyHash();
        differentHash[0]++;
        when(repository.findByClientSubmissionId(submission.clientSubmissionId()))
                .thenReturn(new CalibrationFeedbackRepository.LookupResult.Found(
                        serverId, differentHash, originalTime));
        assertThat(service(true).submit(submission, RECEIVED_AT, "test-revision"))
                .isInstanceOf(CalibrationFeedbackService.Conflict.class);
    }

    @Test
    void refusesUnavailableOrFullStorageBeforeResolution() {
        when(repository.findByClientSubmissionId(any()))
                .thenReturn(new CalibrationFeedbackRepository.LookupResult.Unavailable());
        assertThat(service(true).submit(submission((byte) 1), RECEIVED_AT, "test-revision"))
                .isInstanceOf(CalibrationFeedbackService.Unavailable.class);
        verifyNoInteractions(resolver);

        when(repository.findByClientSubmissionId(any()))
                .thenReturn(new CalibrationFeedbackRepository.LookupResult.NotFound());
        when(repository.status()).thenReturn(CalibrationFeedbackRepository.RepositoryStatus.Available.from(1, 1));
        assertThat(service(true).submit(submission((byte) 2), RECEIVED_AT, "test-revision"))
                .isInstanceOf(CalibrationFeedbackService.Unavailable.class);
        verifyNoInteractions(resolver);
    }

    @Test
    void resolverFailuresDoNotConsumeWriteTokens() {
        when(resolver.resolveLocationId(any())).thenReturn(LocationResolution.notFound());
        CalibrationFeedbackService service = service(true);
        for (int attempt = 0; attempt < 20; attempt++) {
            assertThat(service.submit(submission((byte) attempt), RECEIVED_AT, "test-revision"))
                    .isInstanceOf(CalibrationFeedbackService.LocationNotFound.class);
        }

        when(resolver.resolveLocationId(any())).thenReturn(LocationResolution.temporarilyUnavailable());
        assertThat(service.submit(submission((byte) 21), RECEIVED_AT, "test-revision"))
                .isInstanceOf(CalibrationFeedbackService.Unavailable.class);
        when(resolver.resolveLocationId(any())).thenReturn(LocationResolution.resolved(RESOLVED_LOCATION));
        assertThat(service.submit(submission((byte) 22), RECEIVED_AT, "test-revision"))
                .isInstanceOf(CalibrationFeedbackService.Created.class);
    }

    @Test
    void hostedProviderAdmissionRefusalPrecedesResolutionAndWriteAdmission() {
        AtomicBoolean admit = new AtomicBoolean();
        locationAdmission = operation -> admit.get()
                ? Optional.ofNullable(operation.get())
                : Optional.empty();
        CalibrationFeedbackService service = service(true);

        assertThat(service.submit(submission((byte) 1), RECEIVED_AT, "test-revision"))
                .isInstanceOf(CalibrationFeedbackService.Unavailable.class);
        verifyNoInteractions(resolver);

        admit.set(true);
        for (int attempt = 0; attempt < 12; attempt++) {
            assertThat(service.submit(submission((byte) attempt), RECEIVED_AT, "test-revision"))
                    .isInstanceOf(CalibrationFeedbackService.Created.class);
        }
        assertThat(service.submit(submission((byte) 20), RECEIVED_AT, "test-revision"))
                .isEqualTo(new CalibrationFeedbackService.RateLimited(3_600));
    }

    @Test
    void hostedServiceSharesProviderAdmissionWhileFeedbackBypassesWholeSiteAdmission() {
        MoonRuntimeProperties properties = new MoonRuntimeProperties();
        properties.getResourceLimits().setWholeSiteCapacity(2);
        properties.getResourceLimits().setWholeSiteRefillInterval(Duration.ofHours(1));
        properties.getResourceLimits().setProviderLookupCapacity(1);
        properties.getResourceLimits().setProviderLookupRefillInterval(Duration.ofMinutes(1));
        properties.getResourceLimits().setOpportunityConcurrency(2);

        new ApplicationContextRunner()
                .withBean(MoonRuntimeProperties.class, () -> properties)
                .withBean(Clock.class, () -> Clock.fixed(RECEIVED_AT, ZoneOffset.UTC))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(HostedAlphaResourceLimitFilter.class)
                .withPropertyValues("moon.hosted-alpha.enabled=true")
                .run(context -> {
                    HostedAlphaResourceLimitFilter filter = context.getBean(HostedAlphaResourceLimitFilter.class);
                    CalibrationFeedbackService service =
                            new CalibrationFeedbackService(repository, resolver, filter, true);

                    assertThat(service.submit(submission((byte) 42), RECEIVED_AT, "test-revision"))
                            .isInstanceOf(CalibrationFeedbackService.Created.class);
                    MockHttpServletResponse providerLimited = exchange(filter, "GET", "/api/opportunities");
                    assertThat(providerLimited.getStatus()).isEqualTo(429);
                    assertThat(providerLimited.getHeader("Retry-After")).isEqualTo("60");

                    assertThat(exchange(filter, "GET", "/api/calibration-feedback/v1/capability").getStatus())
                            .isEqualTo(200);
                    assertThat(exchange(filter, "POST", "/api/calibration-feedback/v1/submissions").getStatus())
                            .isEqualTo(200);
                    assertThat(exchange(filter, "GET", "/about").getStatus()).isEqualTo(200);

                    MockHttpServletResponse wholeSiteLimited = exchange(filter, "GET", "/app.js");
                    assertThat(wholeSiteLimited.getStatus()).isEqualTo(429);
                    assertThat(wholeSiteLimited.getHeader("Retry-After")).isEqualTo("3600");
                });
    }

    @Test
    void storesCanonicalLocationFourFactsRevisionAndOneMicrosecondInstant() {
        AtomicInteger astronomyCalls = new AtomicInteger();
        astronomy = (location, instant) -> {
            assertThat(location).isEqualTo(RESOLVED_LOCATION);
            assertThat(instant).isEqualTo(RECEIVED_AT.truncatedTo(ChronoUnit.MICROS));
            astronomyCalls.incrementAndGet();
            return FACTS;
        };
        CalibrationFeedbackSubmission submission = submission((byte) 7);
        UUID serverId = UUID.randomUUID();
        when(repository.store(any())).thenReturn(new CalibrationFeedbackRepository.StoreResult.Created(
                serverId, RECEIVED_AT.truncatedTo(ChronoUnit.MICROS)));

        assertThat(service(true).submit(submission, RECEIVED_AT, "test-revision"))
                .isEqualTo(new CalibrationFeedbackService.Created(
                        submission.clientSubmissionId(), serverId, RECEIVED_AT.truncatedTo(ChronoUnit.MICROS)));
        ArgumentCaptor<CalibrationFeedbackReport> report = ArgumentCaptor.forClass(CalibrationFeedbackReport.class);
        verify(repository).store(report.capture());
        assertThat(report.getValue().locationId()).isEqualTo(RESOLVED_LOCATION.locationId());
        assertThat(report.getValue().opportunityId()).isEqualTo(submission.opportunityId());
        assertThat(report.getValue().astronomyFacts()).isEqualTo(FACTS);
        assertThat(report.getValue().applicationRevision()).isEqualTo("test-revision");
        assertThat(report.getValue().submittedAt()).isEqualTo(RECEIVED_AT.truncatedTo(ChronoUnit.MICROS));
        assertThat(report.getValue().idempotencyHash()).containsExactly(submission.idempotencyHash());
        assertThat(astronomyCalls).hasValue(1);
    }

    @Test
    void productionAstronomyPreservesSubMillisecondReceiptPrecision() {
        HostedAlphaResourceLimitFilter hostedResourceLimits = mock(HostedAlphaResourceLimitFilter.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(0);
            return Optional.ofNullable(operation.get());
        }).when(hostedResourceLimits).executeWithProviderAdmission(any());
        CalibrationFeedbackService service =
                new CalibrationFeedbackService(repository, resolver, hostedResourceLimits, true);
        Instant laterInSameMillisecond = RECEIVED_AT.plusNanos(543_000);

        assertThat(service.submit(submission((byte) 8), RECEIVED_AT, "test-revision"))
                .isInstanceOf(CalibrationFeedbackService.Created.class);
        assertThat(service.submit(submission((byte) 9), laterInSameMillisecond, "test-revision"))
                .isInstanceOf(CalibrationFeedbackService.Created.class);
        ArgumentCaptor<CalibrationFeedbackReport> report = ArgumentCaptor.forClass(CalibrationFeedbackReport.class);
        verify(repository, times(2)).store(report.capture());
        CalibrationFeedbackReport first = report.getAllValues().get(0);
        CalibrationFeedbackReport second = report.getAllValues().get(1);
        assertThat(first.astronomyFacts().moonAltitudeDegrees()).isBetween(-90.0, 90.0);
        assertThat(first.astronomyFacts().moonIlluminationPercent()).isBetween(0.0, 100.0);
        assertThat(first.astronomyFacts().sunAltitudeDegrees()).isBetween(-90.0, 90.0);
        assertThat(first.astronomyFacts().lightBucket()).isNotNull();
        assertThat(first.submittedAt()).isEqualTo(RECEIVED_AT.truncatedTo(ChronoUnit.MICROS));
        assertThat(second.submittedAt()).isEqualTo(laterInSameMillisecond.truncatedTo(ChronoUnit.MICROS));
        assertThat(second.astronomyFacts()).isNotEqualTo(first.astronomyFacts());
    }

    @Test
    void mapsTransactionalReplayConflictCapacityAndOutageWithoutRestoringTokens() {
        CalibrationFeedbackService service = service(true);
        UUID serverId = UUID.randomUUID();
        Instant original = Instant.parse("2026-07-18T01:02:03Z");
        when(repository.store(any()))
                .thenReturn(new CalibrationFeedbackRepository.StoreResult.Replayed(serverId, original))
                .thenReturn(new CalibrationFeedbackRepository.StoreResult.Conflict())
                .thenReturn(new CalibrationFeedbackRepository.StoreResult.CapacityRefused())
                .thenReturn(new CalibrationFeedbackRepository.StoreResult.Unavailable());

        CalibrationFeedbackSubmission first = submission((byte) 1);
        assertThat(service.submit(first, RECEIVED_AT, "test-revision"))
                .isEqualTo(new CalibrationFeedbackService.Replayed(first.clientSubmissionId(), serverId, original));
        assertThat(service.submit(submission((byte) 2), RECEIVED_AT, "test-revision"))
                .isInstanceOf(CalibrationFeedbackService.Conflict.class);
        assertThat(service.submit(submission((byte) 3), RECEIVED_AT, "test-revision"))
                .isInstanceOf(CalibrationFeedbackService.Unavailable.class);
        assertThat(service.submit(submission((byte) 4), RECEIVED_AT, "test-revision"))
                .isInstanceOf(CalibrationFeedbackService.Unavailable.class);
    }

    @Test
    void limiterUsesCompleteMonotonicHoursAndKeepsPartialIntervals() {
        CalibrationFeedbackService service = service(true);
        for (int attempt = 0; attempt < 12; attempt++) {
            assertThat(service.submit(submission((byte) attempt), RECEIVED_AT, "test-revision"))
                    .isInstanceOf(CalibrationFeedbackService.Created.class);
        }
        assertThat(service.submit(submission((byte) 20), RECEIVED_AT, "test-revision"))
                .isEqualTo(new CalibrationFeedbackService.RateLimited(3_600));

        monotonicNanos.addAndGet(Duration.ofHours(1).minusNanos(1).toNanos());
        assertThat(service.submit(submission((byte) 21), RECEIVED_AT, "test-revision"))
                .isEqualTo(new CalibrationFeedbackService.RateLimited(1));
        monotonicNanos.incrementAndGet();
        assertThat(service.submit(submission((byte) 22), RECEIVED_AT, "test-revision"))
                .isInstanceOf(CalibrationFeedbackService.Created.class);
        assertThat(service.submit(submission((byte) 23), RECEIVED_AT, "test-revision"))
                .isEqualTo(new CalibrationFeedbackService.RateLimited(3_600));
    }

    @Test
    void admittedAstronomyAndStoreFailuresStillSpendTokens() {
        AtomicInteger astronomyCalls = new AtomicInteger();
        astronomy = (location, instant) -> {
            if (astronomyCalls.getAndIncrement() == 0) {
                throw new IllegalStateException("test astronomy outage");
            }
            return FACTS;
        };
        when(repository.store(any())).thenReturn(new CalibrationFeedbackRepository.StoreResult.Unavailable());
        CalibrationFeedbackService service = service(true);

        for (int attempt = 0; attempt < 12; attempt++) {
            assertThat(service.submit(submission((byte) attempt), RECEIVED_AT, "test-revision"))
                    .isInstanceOf(CalibrationFeedbackService.Unavailable.class);
        }
        assertThat(service.submit(submission((byte) 20), RECEIVED_AT, "test-revision"))
                .isEqualTo(new CalibrationFeedbackService.RateLimited(3_600));
        assertThat(astronomyCalls).hasValue(12);
        verify(repository, org.mockito.Mockito.times(11)).store(any());
    }

    private CalibrationFeedbackService service(boolean enabled) {
        return new CalibrationFeedbackService(
                repository, resolver, enabled, astronomy, monotonicNanos::get, locationAdmission);
    }

    private static MockHttpServletResponse exchange(
            HostedAlphaResourceLimitFilter filter,
            String method,
            String path
    ) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("198.51.100.10");
        request.setServerName("moon.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response,
                (ignoredRequest, finalResponse) -> ((MockHttpServletResponse) finalResponse).setStatus(200));
        return response;
    }

    private static CalibrationFeedbackSubmission submission(byte marker) {
        byte[] hash = new byte[32];
        hash[0] = marker;
        return new CalibrationFeedbackSubmission(
                CalibrationFeedbackReport.REPORT_SCHEMA_VERSION,
                UUID.randomUUID(),
                "moon-service-3067696",
                "opportunity-1",
                CalibrationFeedbackReport.AmbientLight.GOOD,
                null,
                null,
                hash);
    }
}
