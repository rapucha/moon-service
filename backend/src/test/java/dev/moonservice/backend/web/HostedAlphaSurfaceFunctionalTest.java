package dev.moonservice.backend.web;

import dev.moonservice.backend.admission.HostedAlphaProviderAdmission;
import dev.moonservice.backend.observability.RequestLoggingFilter;
import dev.moonservice.backend.observability.OpenMeteoObservability;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "moon.location.resolver=open-meteo",
                "moon.weather.provider=open-meteo",
                "moon.admin.token=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "moon.hosted-alpha.enabled=true",
                "moon.build.revision=hosted-alpha-test"
        })
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
@Tag("functional")
class HostedAlphaSurfaceFunctionalTest {
    private static final String ADMIN_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final AtomicLong CLOCK_SECONDS = new AtomicLong();
    private static final AtomicBoolean CLOCK_FROZEN = new AtomicBoolean();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private OpenMeteoObservability openMeteoObservability;

    @Autowired
    private HostedAlphaProviderAdmission providerAdmission;

    @TestConfiguration
    static class ResourceLimitClockConfiguration {
        @Bean
        @Primary
        Clock resourceLimitClock() {
            Clock clock = Mockito.mock(Clock.class);
            Mockito.when(clock.instant()).thenAnswer(ignored -> Instant.EPOCH.plusSeconds(
                    CLOCK_FROZEN.get() ? CLOCK_SECONDS.get() : CLOCK_SECONDS.getAndIncrement()));
            return clock;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/search?q=Prague", "/about"})
    void servesApprovedPages(String path) {
        expectHostedHeaders(webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/about.html", "/index.html", "/api.js", "/app.js", "/dom.js", "/format.js",
            "/favicon.svg", "/styles.css", "/sun-marker-aperture-flare.svg", "/terms.js", "/types.js",
            "/moonPathLightBands.js", "/moonPathSilhouetteSymbols.js", "/moonPathSilhouettes.js",
            "/moonPathView.js", "/moonPhaseView.js", "/moonTexture.js", "/opportunityCard.js",
            "/opportunityPreferences.css", "/opportunityPreferences.js",
            "/recentSearches.js", "/responseView.js", "/scoreView.js"
    })
    void servesExactCurrentStaticAssetInventory(String path) {
        webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/app.js", "/readyz"})
    void allowsHeadForApprovedSurface(String path) {
        expectHostedHeaders(webTestClient.head()
                .uri(path)
                .exchange()
                .expectStatus().isOk());
    }

    @Test
    void allowsOpportunityApiToReturnItsCanonicalApplicationError() {
        expectHostedHeaders(webTestClient.get()
                .uri("/api/opportunities")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void admitsProductPostAndRejectsInvalidBodiesBeforeProviderCalls() {
        long geocodingCalls = openMeteoObservability.geocodingSnapshot().calls();
        long weatherCalls = openMeteoObservability.weatherSnapshot().calls();
        for (String body : java.util.List.of(
                "", "{", "null", "[]", "{}", "{\"q\":\"Prague\",\"locationId\":\"prague-cz\"}",
                "{\"q\":\"   \"}", "{\"q\":\"" + "a".repeat(101) + "\"}", "{\"q\":\"\\u0001\"}",
                "{\"locationId\":\"\"}", "{\"locationId\":\"" + "a".repeat(101) + "\"}",
                "{\"q\":\"Prague\",\"unknown\":1}", "{\"q\":\"Prague\",\"preferences\":null}",
                "{\"q\":\"Prague\",\"preferences\":{\"version\":2}}",
                "{\"q\":\"Prague\",\"preferences\":{\"version\":1,\"altitudeDegrees\":"
                        + "{\"minimum\":20,\"maximum\":10}}}",
                "{\"q\":\"Prague\",\"preferences\":{\"version\":1,\"azimuthDegrees\":{}}}",
                "{\"q\":\"Prague\",\"preferences\":{\"version\":1,\"azimuthDegrees\":"
                        + "{\"included\":{\"start\":10,\"end\":20},\"excluded\":{\"start\":30,\"end\":40}}}}",
                "{\"q\":\"Prague\",\"preferences\":{\"version\":1,\"namedPhases\":[\"private-marker\"]}}")) {
            advanceProviderRefill();
            expectProductError(productPost(body, MediaType.APPLICATION_JSON), 400, "invalid_request");
        }
        assertProviderCalls(geocodingCalls, weatherCalls);
    }

    @Test
    void enforcesProductPostMediaTypeAndKnownAndStreamedBodyLimits() {
        long geocodingCalls = openMeteoObservability.geocodingSnapshot().calls();
        long weatherCalls = openMeteoObservability.weatherSnapshot().calls();
        String large = "{\"q\":\"Prague\",\"padding\":\"" + "x".repeat(16_384) + "\"}";

        advanceProviderRefill();
        expectProductError(productPost("{\"q\":\"Prague\"}", MediaType.TEXT_PLAIN),
                415, "unsupported_media_type");
        advanceProviderRefill();
        expectProductError(webTestClient.post().uri("/api/opportunities")
                .bodyValue("{\"q\":\"Prague\"}").exchange(), 415, "unsupported_media_type");
        advanceProviderRefill();
        expectProductError(productPost(large, MediaType.APPLICATION_JSON), 413, "request_too_large");
        advanceProviderRefill();
        expectProductError(webTestClient.post().uri("/api/opportunities")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Flux.just(large), String.class)
                .exchange(), 413, "request_too_large");
        advanceProviderRefill();
        expectProductError(productPost(
                "{}", MediaType.parseMediaType("application/json;charset=UTF-8")),
                400, "invalid_request");
        assertProviderCalls(geocodingCalls, weatherCalls);
    }

    @Test
    void appliesProviderConcurrencyToGetAndProductPost() {
        CLOCK_SECONDS.addAndGet(600);
        try (HostedAlphaProviderAdmission.Admission first = providerAdmission.tryAcquire();
             HostedAlphaProviderAdmission.Admission second = providerAdmission.tryAcquire()) {
            assertThat(first.accepted()).isTrue();
            assertThat(second.accepted()).isTrue();
            expectRateLimited(webTestClient.get().uri("/api/opportunities?q=Prague").exchange(), false);
            expectRateLimited(productPost("{}", MediaType.APPLICATION_JSON), true);
        }
    }

    @Test
    void appliesWholeSiteAdmissionToGetAndProductPost() {
        CLOCK_SECONDS.addAndGet(600);
        CLOCK_FROZEN.set(true);
        try {
            for (int request = 0; request < 40; request++) {
                webTestClient.get().uri("/about").exchange().expectStatus().isOk();
            }
            expectRateLimited(webTestClient.get().uri("/api/opportunities?q=Prague").exchange(), false);
            expectRateLimited(productPost("{}", MediaType.APPLICATION_JSON), true);
        } finally {
            CLOCK_FROZEN.set(false);
            CLOCK_SECONDS.incrementAndGet();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/admin", "/admin/", "/admin/other", "/admin/status/",
            "/api/opportunities/", "/api/opportunities/search", "/api/unknown",
            "/error", "/healthz", "/unknown"
    })
    void hidesUnapprovedPaths(String path) {
        expectHostedHeaders(webTestClient.get()
                .uri(path)
                .header(AdminAccessFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                .exchange()
                .expectStatus().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void authenticatesAdminStatusAndPreventsCaching(String method) {
        expectAdminHeaders(webTestClient.method(HttpMethod.valueOf(method)).uri("/admin/status").exchange()
                .expectStatus().isUnauthorized());
        expectAdminHeaders(webTestClient.method(HttpMethod.valueOf(method)).uri("/admin/status")
                .header(AdminAccessFilter.ADMIN_TOKEN_HEADER, "wrong-token")
                .exchange().expectStatus().isUnauthorized());
        expectAdminHeaders(webTestClient.method(HttpMethod.valueOf(method)).uri("/admin/status")
                .header(AdminAccessFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                .exchange().expectStatus().isOk());
    }

    @Test
    void hidesFixtureEndpointInsteadOfReportingMethodPolicy() {
        expectHostedHeaders(webTestClient.post()
                .uri("/api/opportunities/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void rejectsUnapprovedMethodsOnApprovedPath(String method) {
        expectHostedHeaders(webTestClient.method(HttpMethod.valueOf(method))
                .uri("/admin/status")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().valueEquals("Allow", "GET, HEAD")
                .expectHeader().valueEquals("Cache-Control", "no-store"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE", "OPTIONS"})
    void limitsProductPathMethodsWithoutChangingOtherPaths(String method) {
        expectHostedHeaders(webTestClient.method(HttpMethod.valueOf(method))
                .uri("/api/opportunities")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().valueEquals("Allow", "GET, HEAD, POST"));
    }

    @Test
    void connectorRejectsTraceBeforeTheApplicationFilter() {
        webTestClient.method(HttpMethod.TRACE)
                .uri("/readyz")
                .exchange()
                .expectStatus().isEqualTo(405);
    }

    @Test
    void rejectsBodyOnApprovedGet() {
        expectHostedHeaders(webTestClient.method(HttpMethod.GET)
                .uri("/admin/status")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("Cache-Control", "no-store"));
    }

    @Test
    void doesNotLogForwardedVisitorIdentityOrAdminToken(CapturedOutput output) {
        webTestClient.get()
                .uri("/admin/status")
                .header("Forwarded", "for=forwarded-identity-marker.invalid")
                .header("X-Forwarded-For", "forwarded-identity-marker.invalid")
                .header(AdminAccessFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                .exchange()
                .expectStatus().isOk();

        advanceProviderRefill();
        productPost(
                "{\"q\":\"private-body-marker\",\"preferences\":{\"version\":2}}",
                MediaType.APPLICATION_JSON)
                .expectStatus().isBadRequest();

        assertThat(output)
                .doesNotContain("forwarded-identity-marker.invalid")
                .doesNotContain(ADMIN_TOKEN)
                .doesNotContain("private-body-marker");
    }

    private WebTestClient.ResponseSpec productPost(String body, MediaType contentType) {
        return webTestClient.post().uri("/api/opportunities")
                .contentType(contentType)
                .bodyValue(body)
                .exchange();
    }

    private static void expectProductError(
            WebTestClient.ResponseSpec response,
            int httpStatus,
            String status
    ) {
        expectHostedHeaders(response.expectStatus().isEqualTo(httpStatus)
                .expectHeader().valueEquals("Cache-Control", "no-store"));
        response.expectBody()
                .jsonPath("$.status").isEqualTo(status)
                .jsonPath("$.generatedAt").exists();
    }

    private static void expectRateLimited(WebTestClient.ResponseSpec response, boolean noStore) {
        WebTestClient.ResponseSpec checked = response.expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After");
        if (noStore) {
            checked.expectHeader().valueEquals("Cache-Control", "no-store");
        }
        expectHostedHeaders(checked);
        checked.expectBody().jsonPath("$.status").isEqualTo("rate_limited");
    }

    private static void advanceProviderRefill() {
        CLOCK_SECONDS.addAndGet(60);
    }

    private void assertProviderCalls(long geocodingCalls, long weatherCalls) {
        assertThat(openMeteoObservability.geocodingSnapshot().calls()).isEqualTo(geocodingCalls);
        assertThat(openMeteoObservability.weatherSnapshot().calls()).isEqualTo(weatherCalls);
    }

    private static void expectAdminHeaders(WebTestClient.ResponseSpec response) {
        expectHostedHeaders(response.expectHeader().valueEquals("Cache-Control", "no-store"));
    }

    private static void expectHostedHeaders(WebTestClient.ResponseSpec response) {
        response
                .expectHeader().valueEquals(
                        "Content-Security-Policy",
                        HostedAlphaSurfaceFilter.CONTENT_SECURITY_POLICY)
                .expectHeader().valueEquals("Cross-Origin-Opener-Policy", "same-origin")
                .expectHeader().valueEquals("Cross-Origin-Resource-Policy", "same-origin")
                .expectHeader().valueEquals(
                        "Permissions-Policy",
                        HostedAlphaSurfaceFilter.PERMISSIONS_POLICY)
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader().valueEquals(
                        "Strict-Transport-Security",
                        HostedAlphaSurfaceFilter.STRICT_TRANSPORT_SECURITY)
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().exists(RequestLoggingFilter.REQUEST_ID_HEADER);
    }
}
