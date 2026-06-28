package dev.moonservice.backend.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

class AdminAccessFilterTest {
    @Test
    void passesPublicRequestsWhenAdminTokenIsNotConfigured() throws Exception {
        AdminAccessFilter filter = new AdminAccessFilter("");
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/api/opportunities"), response, (request, servletResponse) -> chainCalled.set(true));

        assertTrue(chainCalled.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void disablesAdminRoutesWhenAdminTokenIsNotConfigured() throws Exception {
        AdminAccessFilter filter = new AdminAccessFilter("");
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/admin/status"), response, (request, servletResponse) -> chainCalled.set(true));

        assertFalse(chainCalled.get());
        assertEquals(404, response.getStatus());
    }

    @Test
    void rejectsAdminRequestWithoutTokenHeader() throws Exception {
        AdminAccessFilter filter = new AdminAccessFilter("secret-token");
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/admin/status"), response, (request, servletResponse) -> chainCalled.set(true));

        assertFalse(chainCalled.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    void rejectsAdminRequestWithWrongTokenHeader() throws Exception {
        AdminAccessFilter filter = new AdminAccessFilter("secret-token");
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockHttpServletRequest request = request("/admin/status");
        request.addHeader(AdminAccessFilter.ADMIN_TOKEN_HEADER, "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> chainCalled.set(true));

        assertFalse(chainCalled.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    void allowsAdminRequestWithConfiguredTokenHeader() throws Exception {
        AdminAccessFilter filter = new AdminAccessFilter("secret-token");
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockHttpServletRequest request = request("/admin/status");
        request.addHeader(AdminAccessFilter.ADMIN_TOKEN_HEADER, "secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> chainCalled.set(true));

        assertTrue(chainCalled.get());
        assertEquals(200, response.getStatus());
    }

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }
}
