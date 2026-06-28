package dev.moonservice.backend.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public final class RequestLoggingFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = requestId(request.getHeader(REQUEST_ID_HEADER));
        long started = System.nanoTime();
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("requestId", requestId);
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error ex) {
            if (response.getStatus() < 400) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            throw ex;
        } finally {
            long durationMillis = Math.round((System.nanoTime() - started) / 1_000_000.0);
            LOGGER.info(
                    "request completed method={} path={} status={} durationMs={} requestId={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMillis,
                    requestId);
            MDC.remove("requestId");
        }
    }

    private static String requestId(String rawRequestId) {
        if (rawRequestId != null && SAFE_REQUEST_ID.matcher(rawRequestId).matches()) {
            return rawRequestId;
        }
        return UUID.randomUUID().toString();
    }
}
