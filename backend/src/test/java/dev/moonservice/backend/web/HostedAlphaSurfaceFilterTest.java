package dev.moonservice.backend.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostedAlphaSurfaceFilterTest {
    private static final String VALID_ADMIN_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "do-not-disclose",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef "
    })
    void rejectsInvalidHostedAdminConfiguration(String token) {
        assertThatThrownBy(() -> new AdminAccessFilter(token, false, true, () -> "unused"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hosted-alpha mode requires an explicit 64-character hexadecimal admin token");
    }

    @Test
    void rejectsHostedTokenGenerationBeforeCallingTheGenerator() {
        assertThatThrownBy(() -> new AdminAccessFilter(VALID_ADMIN_TOKEN, true, true, () -> {
            throw new AssertionError("Hosted configuration must fail before token generation");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("Hosted-alpha mode requires an explicit 64-character hexadecimal admin token");
    }

    @Test
    void leavesExistingSurfaceAndHeadersUnchangedWhenDisabled() throws Exception {
        HostedAlphaSurfaceFilter filter = new HostedAlphaSurfaceFilter(false);
        MockHttpServletRequest request = request("GET", "/admin/status");
        request.addHeader("X-Forwarded-For", "198.51.100.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<MockHttpServletRequest> filteredRequest = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                filteredRequest.set((MockHttpServletRequest) servletRequest));

        assertThat(filteredRequest.get()).isSameAs(request);
        assertThat(filteredRequest.get().getHeader("X-Forwarded-For")).isEqualTo("198.51.100.10");
        assertThat(response.getHeader("Content-Security-Policy")).isNull();
    }

    @Test
    void removesForwardedVisitorIdentityBeforeCallingTheApplication() throws Exception {
        HostedAlphaSurfaceFilter filter = new HostedAlphaSurfaceFilter(true);
        MockHttpServletRequest request = request("GET", "/readyz");
        request.addHeader("Forwarded", "for=198.51.100.10");
        request.addHeader("X-Forwarded-For", "198.51.100.10");
        request.addHeader("X-Real-IP", "198.51.100.10");
        request.addHeader("X-Client-IP", "123");
        request.addHeader("CF-Connecting-IP", "198.51.100.10");
        request.addHeader("Tailscale-User-Login", "visitor@example.invalid");
        request.addHeader("Tailscale-App-Capabilities", "{\"example.invalid/cap\":[]}");
        request.addHeader("X-Request-Id", "safe-request-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            chainCalled.set(true);
            HttpServletRequest forwardedRequest = (HttpServletRequest) servletRequest;
            assertThat(forwardedRequest.getHeader("Forwarded")).isNull();
            assertThat(forwardedRequest.getHeader("x-forwarded-for")).isNull();
            assertThat(Collections.list(forwardedRequest.getHeaders("X-Real-IP"))).isEmpty();
            assertThat(forwardedRequest.getIntHeader("X-Client-IP")).isEqualTo(-1);
            assertThat(Collections.list(forwardedRequest.getHeaderNames()))
                    .noneMatch(name -> name.equalsIgnoreCase("CF-Connecting-IP"))
                    .noneMatch(name -> name.equalsIgnoreCase("Tailscale-User-Login"))
                    .noneMatch(name -> name.equalsIgnoreCase("Tailscale-App-Capabilities"));
            assertThat(forwardedRequest.getHeader("X-Request-Id")).isEqualTo("safe-request-id");
        });

        assertThat(chainCalled).isTrue();
        assertThat(response.getHeader("Content-Security-Policy"))
                .isEqualTo(HostedAlphaSurfaceFilter.CONTENT_SECURITY_POLICY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/admin", "/admin/", "/admin/other", "/admin/status/",
            "/api/opportunities/", "/api/opportunities/search", "/api/unknown", "/healthz",
            "//readyz", "/readyz;unexpected=true", "/READYZ"
    })
    void hidesUnapprovedPathVariants(String path) throws Exception {
        assertThat(rejectedResponse(request("GET", path)).getStatus()).isEqualTo(404);
    }

    @Test
    void rejectsUnapprovedMethodOnApprovedPath() throws Exception {
        MockHttpServletResponse response = rejectedResponse(request("POST", "/readyz"));

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getHeader("Allow")).isEqualTo("GET, HEAD");
    }

    @Test
    void rejectsContentLengthBodyOnApprovedPath() throws Exception {
        MockHttpServletRequest request = request("GET", "/readyz");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));

        assertThat(rejectedResponse(request).getStatus()).isEqualTo(400);
    }

    @Test
    void rejectsTransferEncodedBodyOnApprovedPath() throws Exception {
        MockHttpServletRequest request = request("HEAD", "/readyz");
        request.addHeader("Transfer-Encoding", "chunked");

        assertThat(rejectedResponse(request).getStatus()).isEqualTo(400);
    }

    private static MockHttpServletResponse rejectedResponse(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new HostedAlphaSurfaceFilter(true).doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("Rejected request must not reach the application");
        });
        return response;
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
