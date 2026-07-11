package dev.moonservice.backend.web;

import dev.moonservice.backend.observability.RequestLoggingFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "moon.location.resolver=open-meteo",
                "moon.weather.provider=open-meteo",
                "moon.admin.token=test-admin-token",
                "moon.hosted-alpha.enabled=true",
                "moon.build.revision=hosted-alpha-test"
        })
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class HostedAlphaSurfaceIntegrationTest {
    @Autowired
    private WebTestClient webTestClient;

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
            "/about.html",
            "/api.js",
            "/app.js",
            "/dom.js",
            "/favicon.svg",
            "/format.js",
            "/index.html",
            "/moonPathLightBands.js",
            "/moonPathSilhouetteSymbols.js",
            "/moonPathSilhouettes.js",
            "/moonPathView.js",
            "/moonPhaseView.js",
            "/moonTexture.js",
            "/opportunityCard.js",
            "/recentSearches.js",
            "/responseView.js",
            "/scoreView.js",
            "/styles.css",
            "/sun-marker-aperture-flare.svg",
            "/terms.js",
            "/types.js"
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

    @ParameterizedTest
    @ValueSource(strings = {
            "/admin",
            "/admin/",
            "/api/opportunities/",
            "/api/opportunities/search",
            "/api/unknown",
            "/error",
            "/healthz",
            "/unknown"
    })
    void hidesUnapprovedPaths(String path) {
        expectHostedHeaders(webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isNotFound());
    }

    @Test
    void hidesAdminEvenWithConfiguredToken() {
        expectHostedHeaders(webTestClient.get()
                .uri("/admin/status")
                .header(AdminAccessFilter.ADMIN_TOKEN_HEADER, "test-admin-token")
                .exchange()
                .expectStatus().isNotFound());
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
                .uri("/readyz")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().valueEquals("Allow", "GET, HEAD"));
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
                .uri("/readyz")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest());
    }

    @Test
    void doesNotLogForwardedVisitorIdentity(CapturedOutput output) {
        webTestClient.get()
                .uri("/")
                .header("Forwarded", "for=forwarded-identity-marker.invalid")
                .header("X-Forwarded-For", "forwarded-identity-marker.invalid")
                .exchange()
                .expectStatus().isOk();

        assertThat(output).doesNotContain("forwarded-identity-marker.invalid");
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
