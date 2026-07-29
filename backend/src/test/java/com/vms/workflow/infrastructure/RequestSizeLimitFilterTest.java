package com.vms.workflow.infrastructure;

import com.vms.workflow.security.SecurityProblemWriter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class RequestSizeLimitFilterTest {
    private final SecurityProblemWriter problems = mock(SecurityProblemWriter.class);
    private final RequestSizeLimitFilter filter =
        new RequestSizeLimitFilter(problems, 100);
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void rejectsDeclaredOversizedMutationBeforeReadingBody() throws Exception {
        MockHttpServletRequest request =
            new MockHttpServletRequest("POST", "/api/v1/migrations/jobs");
        request.setContent(new byte[101]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(problems).write(
            request, response, 413, "Payload Too Large",
            "The request body exceeds the permitted size.");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void allowsRequestsInsideTheBound() throws Exception {
        MockHttpServletRequest request =
            new MockHttpServletRequest("PATCH", "/api/v1/attendance");
        request.setContent(new byte[100]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(jakarta.servlet.ServletRequest.class),
            eq(response));
    }

    @Test
    void rejectsOversizedChunkedBodyWithoutContentLength() throws Exception {
        MockHttpServletRequest request =
            new MockHttpServletRequest("POST", "/api/v1/migrations/jobs") {
                @Override
                public long getContentLengthLong() {
                    return -1;
                }
            };
        request.setContent(new byte[101]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(problems).write(
            request, response, 413, "Payload Too Large",
            "The request body exceeds the permitted size.");
        verify(chain, never()).doFilter(
            any(jakarta.servlet.ServletRequest.class),
            any(jakarta.servlet.ServletResponse.class));
    }

    @Test
    void leavesMultipartStreamForServletResolverAndConfiguredLimits()
        throws Exception {
        MockHttpServletRequest request =
            new MockHttpServletRequest("POST", "/api/v1/migrations/jobs");
        request.setContentType("multipart/form-data; boundary=f07");
        request.setContent(new byte[10]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
