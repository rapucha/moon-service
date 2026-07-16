package dev.moonservice.backend.web;

import dev.moonservice.backend.config.MoonRuntimeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostedAlphaResourceLimitFilterTest {
    private static final String ADMIN_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void enforcesWholeSiteBurstRefillRetryHintAndRestartReset() throws Exception {
        MutableClock clock = new MutableClock();
        MoonRuntimeProperties properties = properties(2, Duration.ofSeconds(1), 10, Duration.ofMinutes(1), 2);
        HostedAlphaResourceLimitFilter filter = filter(properties, clock, true);

        assertThat(exchange(filter, request("GET", "/")).getStatus()).isEqualTo(200);
        assertThat(exchange(filter, request("GET", "/app.js")).getStatus()).isEqualTo(200);
        MockHttpServletResponse limited = exchange(filter, request("GET", "/about"));
        assertRateLimited(limited, 1L);
        assertThat(limited.getHeader("Content-Security-Policy"))
                .isEqualTo(HostedAlphaSurfaceFilter.CONTENT_SECURITY_POLICY);

        clock.advance(Duration.ofMillis(500));
        assertThat(exchange(filter, request("GET", "/")).getStatus()).isEqualTo(429);
        clock.advance(Duration.ofMillis(500));
        assertThat(exchange(filter, request("GET", "/")).getStatus()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(2));
        assertThat(exchange(filter, request("GET", "/")).getStatus()).isEqualTo(200);
        assertThat(exchange(filter, request("GET", "/")).getStatus()).isEqualTo(200);
        assertThat(exchange(filter, request("GET", "/")).getStatus()).isEqualTo(429);

        HostedAlphaResourceLimitFilter restarted = filter(properties, clock, true);
        assertThat(exchange(restarted, request("GET", "/")).getStatus()).isEqualTo(200);
        assertThat(exchange(restarted, request("GET", "/")).getStatus()).isEqualTo(200);
    }

    @Test
    void provesProviderDayBoundAndConservativeAttemptArithmetic() throws Exception {
        MutableClock clock = new MutableClock();
        HostedAlphaResourceLimitFilter filter = filter(
                properties(40, Duration.ofSeconds(1), 10, Duration.ofMinutes(1), 2),
                clock,
                true);
        int accepted = 0;

        for (int request = 0; request < 10; request++) {
            assertThat(exchange(filter, opportunityRequest()).getStatus()).isEqualTo(200);
            accepted++;
        }
        assertRateLimited(exchange(filter, opportunityRequest()), 60L);
        for (int minute = 1; minute <= 1_440; minute++) {
            clock.advance(Duration.ofMinutes(1));
            assertThat(exchange(filter, opportunityRequest()).getStatus()).isEqualTo(200);
            accepted++;
            assertRateLimited(exchange(filter, opportunityRequest()), 60L);
        }

        assertThat(accepted).isEqualTo(1_450);
        int worstCaseAttempts = accepted * 4;
        assertThat(worstCaseAttempts).isEqualTo(5_800);
        assertThat(10_000 - worstCaseAttempts).isEqualTo(4_200);
    }

    @Test
    void rejectsAThirdConcurrentOpportunityAndReleasesPermits() throws Exception {
        MutableClock clock = new MutableClock();
        HostedAlphaResourceLimitFilter filter = filter(
                properties(20, Duration.ofSeconds(1), 10, Duration.ofMinutes(1), 2),
                clock,
                true);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        FilterChain blockingChain = (request, response) -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new ServletException("Timed out waiting to release test request");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ServletException(ex);
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MockHttpServletResponse> first = executor.submit(() ->
                    exchange(filter, opportunityRequest(), blockingChain));
            Future<MockHttpServletResponse> second = executor.submit(() ->
                    exchange(filter, opportunityRequest(), blockingChain));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertRateLimited(exchange(filter, opportunityRequest()), 1L);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
            assertThat(second.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
            assertThat(exchange(filter, opportunityRequest()).getStatus()).isEqualTo(200);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void countsEveryAdminAttemptBeforePolicyAndPreservesDockerReadiness() throws Exception {
        MutableClock clock = new MutableClock();
        HostedAlphaResourceLimitFilter filter = filter(
                properties(3, Duration.ofSeconds(1), 1, Duration.ofMinutes(1), 2),
                clock,
                true);
        HostedAlphaSurfaceFilter surfaceFilter = new HostedAlphaSurfaceFilter(true);
        AdminAccessFilter adminAccessFilter = new AdminAccessFilter(ADMIN_TOKEN, false, true, () -> "unused");
        FilterChain hostedAdminChain = (request, response) -> surfaceFilter.doFilter(
                request,
                response,
                (surfaceRequest, surfaceResponse) -> adminAccessFilter.doFilter(
                        surfaceRequest,
                        surfaceResponse,
                        (ignored, finalResponse) -> ((HttpServletResponse) finalResponse).setStatus(200)));

        assertThat(exchange(filter, request("GET", "/admin/status"), hostedAdminChain).getStatus()).isEqualTo(401);
        assertThat(exchange(filter, request("POST", "/admin/status"), hostedAdminChain).getStatus()).isEqualTo(405);
        MockHttpServletRequest bodyRequest = request("GET", "/admin/status");
        bodyRequest.setContent("{}".getBytes());
        assertThat(exchange(filter, bodyRequest, hostedAdminChain).getStatus()).isEqualTo(400);

        MockHttpServletRequest authenticated = request("GET", "/admin/status");
        authenticated.addHeader(AdminAccessFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN);
        MockHttpServletResponse adminLimited = exchange(filter, authenticated, hostedAdminChain);
        assertRateLimited(adminLimited, 1L);
        assertThat(adminLimited.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(adminLimited.getHeader("Content-Security-Policy"))
                .isEqualTo(HostedAlphaSurfaceFilter.CONTENT_SECURITY_POLICY);

        MockHttpServletRequest dockerReadiness = new MockHttpServletRequest("GET", "/readyz");
        dockerReadiness.setRemoteAddr("127.0.0.1");
        dockerReadiness.setServerName("localhost");
        assertThat(exchange(filter, dockerReadiness).getStatus()).isEqualTo(200);
        assertThat(exchange(filter, request("GET", "/readyz")).getStatus()).isEqualTo(429);

        clock.advance(Duration.ofSeconds(3));
        assertThat(exchange(filter, opportunityRequest()).getStatus()).isEqualTo(200);
        assertThat(exchange(filter, opportunityRequest()).getStatus()).isEqualTo(429);
    }

    @Test
    void activatesOnlyForHostedModeAndRejectsHostedRetryDrift() throws Exception {
        MoonRuntimeProperties properties = properties(1, Duration.ofSeconds(1), 1, Duration.ofMinutes(1), 1);
        properties.getOpenMeteo().setMaxTransportRetries(2);
        MutableClock clock = new MutableClock();
        HostedAlphaResourceLimitFilter disabled = filter(properties, clock, false);
        assertThat(exchange(disabled, request("GET", "/")).getStatus()).isEqualTo(200);
        assertThat(exchange(disabled, request("GET", "/")).getStatus()).isEqualTo(200);
        assertThatThrownBy(() -> filter(properties, clock, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at most one Open-Meteo transport retry");
        properties.getOpenMeteo().setMaxTransportRetries(1);
        properties.getResourceLimits().setProviderLookupRefillInterval(Duration.ofSeconds(1));
        assertThatThrownBy(() -> filter(properties, clock, true))
                .hasMessageContaining("weaken the accepted safety bounds");
        Order resourceOrder = AnnotatedElementUtils.findMergedAnnotation(HostedAlphaResourceLimitFilter.class, Order.class);
        Order surfaceOrder = AnnotatedElementUtils.findMergedAnnotation(HostedAlphaSurfaceFilter.class, Order.class);
        assertThat(resourceOrder).isNotNull();
        assertThat(surfaceOrder).isNotNull();
        assertThat(resourceOrder.value()).isLessThan(surfaceOrder.value());
    }

    private static HostedAlphaResourceLimitFilter filter(
            MoonRuntimeProperties properties,
            Clock clock,
            boolean enabled
    ) {
        return new HostedAlphaResourceLimitFilter(properties, clock, new ObjectMapper(), enabled);
    }

    private static MoonRuntimeProperties properties(
            int wholeSiteCapacity,
            Duration wholeSiteRefill,
            int providerCapacity,
            Duration providerRefill,
            int concurrency
    ) {
        MoonRuntimeProperties properties = new MoonRuntimeProperties();
        properties.getResourceLimits().setWholeSiteCapacity(wholeSiteCapacity);
        properties.getResourceLimits().setWholeSiteRefillInterval(wholeSiteRefill);
        properties.getResourceLimits().setProviderLookupCapacity(providerCapacity);
        properties.getResourceLimits().setProviderLookupRefillInterval(providerRefill);
        properties.getResourceLimits().setOpportunityConcurrency(concurrency);
        return properties;
    }
    private static MockHttpServletRequest opportunityRequest() {
        return request("GET", "/api/opportunities");
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("198.51.100.10");
        request.setServerName("moon.example");
        return request;
    }
    private static MockHttpServletResponse exchange(
            HostedAlphaResourceLimitFilter filter,
            MockHttpServletRequest request
    ) throws Exception {
        return exchange(filter, request, (ignoredRequest, ignoredResponse) -> {
        });
    }
    private static MockHttpServletResponse exchange(
            HostedAlphaResourceLimitFilter filter,
            MockHttpServletRequest request,
            FilterChain filterChain
    ) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        return response;
    }
    private static void assertRateLimited(MockHttpServletResponse response, long retryAfterSeconds) throws Exception {
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo(Long.toString(retryAfterSeconds));
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains(
                "\"status\":\"rate_limited\"",
                "\"retryAfterSeconds\":" + retryAfterSeconds);
    }
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-13T00:00:00Z");

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
        @Override
        public Instant instant() {
            return instant;
        }
    }
}
