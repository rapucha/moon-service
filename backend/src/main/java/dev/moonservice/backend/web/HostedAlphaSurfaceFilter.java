package dev.moonservice.backend.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class HostedAlphaSurfaceFilter extends OncePerRequestFilter {
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; base-uri 'none'; "
            + "connect-src 'self'; form-action 'self'; frame-ancestors 'none'; "
            + "img-src 'self' data:; object-src 'none'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'";
    static final String PERMISSIONS_POLICY = "camera=(), geolocation=(), microphone=()";
    static final String STRICT_TRANSPORT_SECURITY = "max-age=31536000";

    private static final Set<String> APPROVED_PATHS = Set.of(
            "/", "/about", "/about.html", "/index.html", "/search",
            "/admin/status", "/api/opportunities", "/readyz",
            "/api.js", "/app.js", "/dom.js", "/format.js", "/terms.js", "/types.js",
            "/favicon.svg", "/styles.css", "/sun-marker-aperture-flare.svg",
            "/moonPathLightBands.js", "/moonPathSilhouetteSymbols.js", "/moonPathSilhouettes.js",
            "/moonPathView.js", "/moonPhaseView.js", "/moonTexture.js", "/opportunityCard.js",
            "/recentSearches.js", "/responseView.js", "/scoreView.js"
    );
    private static final Set<String> FORWARDED_IDENTITY_HEADERS = Set.of(
            "cf-connecting-ip", "forwarded", "true-client-ip", "x-client-ip", "x-forwarded-for", "x-real-ip",
            "tailscale-app-capabilities", "tailscale-user-login", "tailscale-user-name", "tailscale-user-profile-pic"
    );

    private final boolean enabled;

    @Autowired
    public HostedAlphaSurfaceFilter(@Value("${moon.hosted-alpha.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        addSecurityHeaders(response);

        String path = applicationPath(request);
        if (path.equals("/admin/status")) {
            response.setHeader("Cache-Control", "no-store");
        }
        if (!APPROVED_PATHS.contains(path)) {
            reject(response, HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!isApprovedMethod(request.getMethod())) {
            response.setHeader("Allow", "GET, HEAD");
            reject(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        if (hasFramedBody(request)) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        filterChain.doFilter(new ForwardedIdentityIgnoringRequest(request), response);
    }

    private static void addSecurityHeaders(HttpServletResponse response) {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Strict-Transport-Security", STRICT_TRANSPORT_SECURITY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
    }

    private static boolean isApprovedMethod(String method) {
        return method.equals("GET") || method.equals("HEAD");
    }

    private static boolean hasFramedBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
    }

    private static String applicationPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    private static void reject(HttpServletResponse response, int status) {
        response.setStatus(status);
        response.setContentLength(0);
    }

    private static boolean isForwardedIdentityHeader(String name) {
        return name != null && FORWARDED_IDENTITY_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    private static final class ForwardedIdentityIgnoringRequest extends HttpServletRequestWrapper {
        private ForwardedIdentityIgnoringRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return isForwardedIdentityHeader(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isForwardedIdentityHeader(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public long getDateHeader(String name) {
            return isForwardedIdentityHeader(name) ? -1L : super.getDateHeader(name);
        }

        @Override
        public int getIntHeader(String name) {
            return isForwardedIdentityHeader(name) ? -1 : super.getIntHeader(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Enumeration<String> headerNames = super.getHeaderNames();
            if (headerNames == null) {
                return Collections.emptyEnumeration();
            }
            List<String> retained = Collections.list(headerNames).stream()
                    .filter(name -> !isForwardedIdentityHeader(name))
                    .toList();
            return Collections.enumeration(retained);
        }
    }
}
