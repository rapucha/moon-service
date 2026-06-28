package dev.moonservice.backend.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class AdminAccessFilter extends OncePerRequestFilter {
    public static final String ADMIN_TOKEN_HEADER = "X-Moon-Admin-Token";

    private final boolean adminRoutesEnabled;
    private final byte[] configuredToken;

    public AdminAccessFilter(@Value("${moon.admin.token:}") String rawToken) {
        String token = rawToken == null ? "" : rawToken.strip();
        this.adminRoutesEnabled = !token.isEmpty();
        this.configuredToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!isAdminPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!adminRoutesEnabled) {
            reject(response, HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!matchesConfiguredToken(request.getHeader(ADMIN_TOKEN_HEADER))) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean matchesConfiguredToken(String rawHeader) {
        if (rawHeader == null) {
            return false;
        }
        byte[] provided = rawHeader.strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(configuredToken, provided);
    }

    private static boolean isAdminPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.equals("/admin") || path.startsWith("/admin/");
    }

    private static void reject(HttpServletResponse response, int status) {
        response.setStatus(status);
    }
}
