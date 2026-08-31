package dev.moonservice.backend.web;

import dev.moonservice.backend.admission.HostedAlphaProviderAdmission;
import dev.moonservice.backend.observability.RequestLoggingFilter;
import dev.moonservice.backend.observability.OpenMeteoObservability;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

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
    @MethodSource("packagedStaticPaths")
    void servesPackagedStaticFiles(String path) {
        webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
    }

    @Test
    void servesWeatherRankingPreferenceAsJavaScript() {
        webTestClient.get()
                .uri("/weatherRankingPreference.js")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().contentTypeCompatibleWith("text/javascript");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/app.js", "/cameraFramingPreview.css", "/cameraFramingPreview.js",
            "/camera-preview/level-0.webp", "/camera-preview/level-1.webp",
            "/camera-preview/level-2.webp", "/camera-preview/level-3.webp",
            "/camera-preview/level-4.webp", "/camera-preview/level-5.webp",
            "/cameraReferenceScene.js", "/cameraSetup.js", "/currentMoonCard.js",
            "/highResolutionMoonRenderer.js", "/moon-textures/lroc_color_2k.jpg",
            "/lunarEclipseCard.js", "/lunarEclipseRenderer.js",
            "/moonEventView.css", "/moonEventView.js", "/planningView.js",
            "/skyDomeView.js", "/readyz"
    })
    void allowsHeadForApprovedSurface(String path) {
        expectHostedHeaders(webTestClient.head()
                .uri(path)
                .exchange()
                .expectStatus().isOk());
    }

    @Test
    void servesExactMoonTextureBytes() throws NoSuchAlgorithmException {
        WebTestClient textureClient = webTestClient.mutate()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(500_000))
                .build();
        WebTestClient.ResponseSpec response = textureClient.get()
                .uri("/moon-textures/lroc_color_2k.jpg")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_JPEG)
                .expectHeader().contentLength(457_942);
        expectHostedHeaders(response);

        byte[] body = response.expectBody().returnResult().getResponseBody();
        assertThat(body).hasSize(457_942);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
        assertThat(HexFormat.of().formatHex(digest))
                .isEqualTo("f7130a1822681fa7512d7dcfd40db8c10b9ba4f06777910348698260ed7a2170");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            /camera-preview/level-0.webp | 596502 | 0f08e48ac32a05128c7ead88a8303168cd8c046612d690d4d6fc481f9446e55a
            /camera-preview/level-1.webp | 523112 | 289d1c681be4c9a8a34dfe36a33b1709989f3a5525f6199d6c51addb23708b0b
            /camera-preview/level-2.webp | 531956 | 62c9e08d65b731cf3d73845f119144402139002b1552f09febe4482f6fd31966
            /camera-preview/level-3.webp | 594140 | 3a3adffbc3097d59894e7569396b64365727493d5cc215937d73b2193b0847ac
            /camera-preview/level-4.webp | 511290 | d74bb2f12970f3df918dfb91ce97b9681380c87ceaba6bd741c84cfb95196b6f
            /camera-preview/level-5.webp | 417948 | 11e901adc1f355f8480d3a66f869e98d871547774174c7e7a93d142e41eff253
            """)
    void servesExactCameraPreviewBytes(String path, int size, String expectedDigest)
            throws NoSuchAlgorithmException {
        WebTestClient imageClient = webTestClient.mutate()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(700_000))
                .build();
        WebTestClient.ResponseSpec response = imageClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.parseMediaType("image/webp"))
                .expectHeader().contentLength(size);
        expectHostedHeaders(response);

        byte[] body = response.expectBody().returnResult().getResponseBody();
        assertThat(body).hasSize(size);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
        assertThat(HexFormat.of().formatHex(digest)).isEqualTo(expectedDigest);
    }

    @Test
    void allowsOpportunityApiToReturnItsCanonicalApplicationError() {
        expectHostedHeaders(webTestClient.get()
                .uri("/api/opportunities")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void exposesExactAtomFeed(String method) {
        advanceProviderRefill();

        expectHostedHeaders(webTestClient.method(HttpMethod.valueOf(method))
                .uri("/feeds/atom")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("Cache-Control", "no-store"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void exposesExactCalendarFeed(String method) {
        advanceProviderRefill();

        expectHostedHeaders(webTestClient.method(HttpMethod.valueOf(method))
                .uri("/calendars/opportunities.ics")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("Cache-Control", "no-store"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void exposesExactIndividualCalendarPath(String method) {
        advanceProviderRefill();

        expectHostedHeaders(webTestClient.method(HttpMethod.valueOf(method))
                .uri("/o/.ics?locationId=moon-service-3067696")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("Cache-Control", "no-store"));
    }

    @Test
    void exposesPlanningPostWithoutCorsAndAllowsItsFramedBody() {
        advanceProviderRefill();

        WebTestClient.ResponseSpec response = webTestClient.post()
                .uri("/api/opportunities/planning")
                .header("Origin", "https://other-origin.invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");

        expectProductError(response, 400, "invalid_request");
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
    void appliesProviderConcurrencyToOpportunityRequestsWithoutControllerWork() {
        CLOCK_SECONDS.addAndGet(600);
        long geocodingCalls = openMeteoObservability.geocodingSnapshot().calls();
        long weatherCalls = openMeteoObservability.weatherSnapshot().calls();
        try (HostedAlphaProviderAdmission.Admission first = providerAdmission.tryAcquire();
             HostedAlphaProviderAdmission.Admission second = providerAdmission.tryAcquire()) {
            assertThat(first.accepted()).isTrue();
            assertThat(second.accepted()).isTrue();
            expectRateLimited(webTestClient.get().uri("/api/opportunities?q=Prague").exchange(), false);
            expectRateLimited(productPost("{}", MediaType.APPLICATION_JSON), true);
            expectRateLimited(planningPost(validPlanningBody()), true);
            expectRateLimited(webTestClient.get()
                    .uri("/feeds/atom?locationId=private-feed-location"
                            + "&preferences=%7B%22version%22%3A1%2C%22namedPhases%22%3A"
                            + "%5B%22full_moon%22%5D%7D")
                    .exchange(), true);
            expectHeadRateLimited(webTestClient.head()
                    .uri("/feeds/atom?locationId=private-feed-location").exchange(), true);
            expectRateLimited(webTestClient.get()
                    .uri("/calendars/opportunities.ics?locationId=private-calendar-feed-location")
                    .exchange(), true);
            expectHeadRateLimited(webTestClient.head()
                    .uri("/calendars/opportunities.ics?locationId=private-calendar-feed-location")
                    .exchange(), true);
            expectRateLimited(webTestClient.get()
                    .uri("/o/result.ics?locationId=private-calendar-location").exchange(), true);
            expectHeadRateLimited(webTestClient.head()
                    .uri("/o/result.ics?locationId=private-calendar-location").exchange(), true);
        }
        assertProviderCalls(geocodingCalls, weatherCalls);
    }

    @Test
    void appliesWholeSiteAdmissionToOpportunityRequests() {
        CLOCK_SECONDS.addAndGet(600);
        CLOCK_FROZEN.set(true);
        try {
            for (int request = 0; request < 40; request++) {
                webTestClient.get().uri("/about").exchange().expectStatus().isOk();
            }
            expectRateLimited(webTestClient.get().uri("/api/opportunities?q=Prague").exchange(), false);
            expectRateLimited(productPost("{}", MediaType.APPLICATION_JSON), true);
            expectRateLimited(planningPost(validPlanningBody()), true);
            expectRateLimited(webTestClient.get()
                    .uri("/feeds/atom?locationId=private-feed-location").exchange(), true);
            expectHeadRateLimited(webTestClient.head()
                    .uri("/feeds/atom?locationId=private-feed-location").exchange(), true);
            expectRateLimited(webTestClient.get()
                    .uri("/calendars/opportunities.ics?locationId=private-calendar-feed-location")
                    .exchange(), true);
            expectHeadRateLimited(webTestClient.head()
                    .uri("/calendars/opportunities.ics?locationId=private-calendar-feed-location")
                    .exchange(), true);
            expectRateLimited(webTestClient.get()
                    .uri("/o/result.ics?locationId=private-calendar-location").exchange(), true);
            expectHeadRateLimited(webTestClient.head()
                    .uri("/o/result.ics?locationId=private-calendar-location").exchange(), true);
        } finally {
            CLOCK_FROZEN.set(false);
            CLOCK_SECONDS.incrementAndGet();
        }
    }

    @Test
    void appliesWholeSiteAdmissionBeforePlanningProviderAdmission() {
        CLOCK_SECONDS.addAndGet(600);
        CLOCK_FROZEN.set(true);
        try {
            for (int request = 0; request < 40; request++) {
                webTestClient.get().uri("/about").exchange().expectStatus().isOk();
            }
            expectRateLimited(planningPost(validPlanningBody()), true);

            for (int request = 0; request < 10; request++) {
                CLOCK_SECONDS.incrementAndGet();
                expectProductError(planningPost("{}"), 400, "invalid_request");
            }
            CLOCK_SECONDS.incrementAndGet();
            expectRateLimited(planningPost("{}"), true);
        } finally {
            CLOCK_FROZEN.set(false);
            CLOCK_SECONDS.incrementAndGet();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/admin", "/admin/", "/admin/other", "/admin/status/",
            "/api/opportunities/", "/api/opportunities/search", "/api/unknown",
            "/api/opportunities/planning/", "/api/opportunities/Planning",
            "/api/opportunities/planning;other", "/PlanningView.js",
            "/feeds/atom/", "/feeds/Atom", "/feeds/atom/other",
            "/calendars/opportunities.ics/", "/calendars/other.ics",
            "/o", "/o/result", "/o/result.txt", "/o/result.ics/", "/o/nested/result.ics",
            "/LunarEclipseCard.js", "/lunarEclipseCard.js/",
            "/LunarEclipseRenderer.js", "/lunarEclipseRenderer.js/",
            "/error", "/healthz", "/unknown"
    })
    void hidesUnapprovedPaths(String path) {
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri(path)
                .header(AdminAccessFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                .exchange()
                .expectStatus().isNotFound();
        if (path.equals("/o") || path.startsWith("/o/")) {
            response.expectHeader().valueEquals("Cache-Control", "no-store");
        }
        expectHostedHeaders(response);
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

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void allowsOnlyGetAndHeadOnPublicExportPaths(String method) {
        for (String path : List.of(
                "/feeds/atom", "/calendars/opportunities.ics", "/o/result.ics")) {
            WebTestClient.ResponseSpec response = webTestClient.method(HttpMethod.valueOf(method))
                    .uri(path)
                    .exchange()
                    .expectStatus().isEqualTo(405)
                    .expectHeader().valueEquals("Allow", "GET, HEAD")
                    .expectHeader().valueEquals("Cache-Control", "no-store");
            expectHostedHeaders(response);
            response.expectBody().isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void allowsOnlyPostOnExactPlanningPath(String method) {
        expectHostedHeaders(webTestClient.method(HttpMethod.valueOf(method))
                .uri("/api/opportunities/planning")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().valueEquals("Allow", "POST")
                .expectHeader().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void connectorRejectsTraceBeforeTheApplicationFilter() {
        webTestClient.method(HttpMethod.TRACE)
                .uri("/readyz")
                .exchange()
                .expectStatus().isEqualTo(405);
    }

    @Test
    void rejectsBodyOnApprovedGets() {
        for (String path : List.of(
                "/admin/status", "/feeds/atom",
                "/calendars/opportunities.ics?locationId=location",
                "/o/result.ics?locationId=location")) {
            advanceProviderRefill();
            WebTestClient.ResponseSpec response = webTestClient.method(HttpMethod.GET)
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().valueEquals("Cache-Control", "no-store");
            expectHostedHeaders(response);
            response.expectBody().isEmpty();
        }
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

        advanceProviderRefill();
        webTestClient.post()
                .uri("/api/opportunities/planning")
                .header("Forwarded", "for=planning-forwarded-marker.invalid")
                .header("X-Forwarded-For", "planning-forwarded-marker.invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"locationId":"private-planning-marker","preferences":{"version":2}}
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        advanceProviderRefill();
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/feeds/atom")
                        .queryParam("locationId", "private-feed-location-marker-" + "x".repeat(101))
                        .build())
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(output)
                .doesNotContain("forwarded-identity-marker.invalid")
                .doesNotContain("planning-forwarded-marker.invalid")
                .doesNotContain(ADMIN_TOKEN)
                .doesNotContain("private-body-marker")
                .doesNotContain("private-planning-marker")
                .doesNotContain("private-feed-location-marker");
    }

    private WebTestClient.ResponseSpec productPost(String body, MediaType contentType) {
        return webTestClient.post().uri("/api/opportunities")
                .contentType(contentType)
                .bodyValue(body)
                .exchange();
    }

    private WebTestClient.ResponseSpec planningPost(String body) {
        return webTestClient.post().uri("/api/opportunities/planning")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private static String validPlanningBody() {
        return """
                {"locationId":"private-planning-location","preferences":{"version":1}}
                """;
    }

    private static List<String> packagedStaticPaths() throws IOException {
        Path staticRoot = new ClassPathResource("static").getFile().toPath();
        try (Stream<Path> paths = Files.walk(staticRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(staticRoot::relativize)
                    .map(path -> "/" + path.toString().replace(File.separatorChar, '/'))
                    .sorted()
                    .toList();
        }
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

    private static void expectHeadRateLimited(WebTestClient.ResponseSpec response, boolean noStore) {
        WebTestClient.ResponseSpec checked = response.expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().exists("Content-Length");
        if (noStore) {
            checked.expectHeader().valueEquals("Cache-Control", "no-store");
        }
        expectHostedHeaders(checked);
        checked.expectBody().isEmpty();
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
