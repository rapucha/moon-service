package dev.moonservice.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterTest {
    @Test
    void logsRequestWithoutRawQueryString(CapturedOutput output) throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/opportunities");
        request.setQueryString("q=Sensitive%20Location");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> ((MockHttpServletResponse) servletResponse)
                .setStatus(200);

        filter.doFilter(request, response, chain);

        assertNotNull(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER));
        assertThat(output).contains("method=GET");
        assertThat(output).contains("path=/api/opportunities");
        assertThat(output).doesNotContain("Sensitive");
        assertThat(output).doesNotContain("q=");
    }
}
