package dev.moonservice.backend.web;

import dev.moonservice.backend.admission.HostedAlphaProviderAdmission;
import dev.moonservice.backend.config.MoonRuntimeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
final class HostedAlphaResourceLimitFilter extends OncePerRequestFilter {
    private static final String OPPORTUNITY_PATH = "/api/opportunities";
    private static final String ADMIN_STATUS_PATH = "/admin/status";
    private static final String READINESS_PATH = "/readyz";

    private final boolean enabled;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final TokenBucket wholeSiteBucket;
    private final HostedAlphaProviderAdmission providerAdmission;

    HostedAlphaResourceLimitFilter(
            MoonRuntimeProperties properties,
            Clock clock,
            ObjectMapper objectMapper,
            HostedAlphaProviderAdmission providerAdmission,
            @Value("${moon.hosted-alpha.enabled:false}") boolean enabled
    ) {
        MoonRuntimeProperties.ResourceLimits limits = properties.getResourceLimits();
        if (enabled && (limits.getWholeSiteCapacity() > 40
                || limits.getWholeSiteRefillInterval().compareTo(Duration.ofSeconds(1)) < 0)) {
            throw new IllegalStateException("Hosted-alpha resource settings weaken the accepted safety bounds");
        }
        this.enabled = enabled;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.providerAdmission = Objects.requireNonNull(providerAdmission, "providerAdmission");
        this.wholeSiteBucket = new TokenBucket(
                limits.getWholeSiteCapacity(),
                limits.getWholeSiteRefillInterval(),
                clock.instant());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!enabled || isDockerReadinessProbe(request) || isCalibrationFeedbackRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = HostedAlphaSurfaceFilter.applicationPath(request);
        Admission wholeSiteAdmission = wholeSiteBucket.consumeTokenIfAvailable(clock.instant());
        if (!wholeSiteAdmission.accepted()) {
            reject(request, response, path, wholeSiteAdmission.retryAfterSeconds());
            return;
        }

        if (!isProviderBackedOpportunityRequest(request, path)) {
            filterChain.doFilter(request, response);
            return;
        }

        try (HostedAlphaProviderAdmission.Admission admission = providerAdmission.tryAcquire()) {
            if (!admission.accepted()) {
                reject(request, response, path, admission.retryAfterSeconds());
                return;
            }
            filterChain.doFilter(request, response);
        }
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            String path,
            long retryAfterSeconds
    ) throws IOException {
        HostedAlphaSurfaceFilter.addSecurityHeaders(response);
        if (ADMIN_STATUS_PATH.equals(path)) {
            response.setHeader("Cache-Control", "no-store");
        }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        byte[] body = objectMapper.writeValueAsBytes(new RateLimitedResponse(
                "rate_limited",
                "Too many requests. Please try again shortly.",
                retryAfterSeconds));
        response.setContentLength(body.length);
        if (!"HEAD".equals(request.getMethod())) {
            response.getOutputStream().write(body);
        }
    }

    private static boolean isProviderBackedOpportunityRequest(HttpServletRequest request, String path) {
        return OPPORTUNITY_PATH.equals(path)
                && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()));
    }

    private static boolean isCalibrationFeedbackRequest(HttpServletRequest request) {
        return HostedAlphaSurfaceFilter.isFeedbackPath(HostedAlphaSurfaceFilter.applicationPath(request));
    }

    private static boolean isDockerReadinessProbe(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && READINESS_PATH.equals(HostedAlphaSurfaceFilter.applicationPath(request))
                && request.getContentLengthLong() <= 0
                && request.getHeader("Transfer-Encoding") == null
                && request.getRemoteAddr() != null
                && isLoopback(request.getRemoteAddr())
                && "localhost".equalsIgnoreCase(request.getServerName());
    }

    private static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "0:0:0:0:0:0:0:1".equals(address) || "::1".equals(address);
    }

    private record RateLimitedResponse(String status, String message, long retryAfterSeconds) {
    }

    private record Admission(boolean accepted, long retryAfterSeconds) {
    }

    private static final class TokenBucket {
        private final int capacity;
        private final Duration refillInterval;
        private int tokens;
        private Instant refilledAt;

        private TokenBucket(int capacity, Duration refillInterval, Instant startedAt) {
            this.capacity = capacity;
            this.refillInterval = refillInterval;
            this.tokens = capacity;
            this.refilledAt = startedAt;
        }

        private synchronized Admission consumeTokenIfAvailable(Instant now) {
            refill(now);
            if (tokens > 0) {
                tokens--;
                return new Admission(true, 0L);
            }
            Duration elapsed = now.isBefore(refilledAt) ? Duration.ZERO : Duration.between(refilledAt, now);
            long retryAfterMillis = refillInterval.minus(elapsed).toMillis();
            return new Admission(false, Math.max(1L, Math.floorDiv(retryAfterMillis + 999L, 1_000L)));
        }

        private void refill(Instant now) {
            if (now.isBefore(refilledAt)) {
                return;
            }
            long intervals = Duration.between(refilledAt, now).dividedBy(refillInterval);
            if (intervals == 0L) {
                return;
            }
            tokens = (int) Math.min(capacity, tokens + Math.min(intervals, capacity));
            refilledAt = refilledAt.plus(refillInterval.multipliedBy(intervals));
        }
    }
}
