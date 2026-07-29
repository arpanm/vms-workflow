package com.vms.workflow.infrastructure;

import com.vms.workflow.security.SecurityProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Rejects declared oversized request bodies before authentication, parsing or
 * persistence. The reverse proxy/container must independently reject
 * unbounded chunked bodies because they do not carry a Content-Length.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestSizeLimitFilter extends OncePerRequestFilter {
    private static final Set<String> BODY_METHODS =
        Set.of("POST", "PUT", "PATCH");

    private final SecurityProblemWriter problems;
    private final long maxBytes;

    public RequestSizeLimitFilter(
        SecurityProblemWriter problems,
        @Value("${vms.security.max-json-request-bytes:1048576}") long maxBytes
    ) {
        if (maxBytes < 1 || maxBytes > 4_194_304L) {
            throw new IllegalArgumentException(
                "Non-multipart request size limit is outside the supported range.");
        }
        this.problems = problems;
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        boolean multipart = contentType != null
            && contentType.toLowerCase(java.util.Locale.ROOT)
                .startsWith("multipart/");
        return !BODY_METHODS.contains(request.getMethod().toUpperCase())
            || multipart;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            problems.write(
                request, response, 413, "Payload Too Large",
                "The request body exceeds the permitted size.");
            return;
        }
        BoundedBodyRequest bounded = BoundedBodyRequest.read(request, maxBytes);
        if (bounded == null) {
            problems.write(
                request, response, 413, "Payload Too Large",
                "The request body exceeds the permitted size.");
            return;
        }
        filterChain.doFilter(bounded, response);
    }

    /**
     * Reads at most limit+1 bytes before authentication or structured-body
     * parsing and then exposes only the bounded replayable body downstream.
     * Multipart is deliberately handled by the servlet multipart resolver and
     * its independently configured file/request limits; consuming it here
     * would make {@code getParts()} observe an exhausted request stream.
     */
    private static final class BoundedBodyRequest
        extends HttpServletRequestWrapper {
        private final byte[] body;

        private BoundedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        static BoundedBodyRequest read(
            HttpServletRequest request, long maxBytes
        ) throws IOException {
            byte[] bytes = request.getInputStream()
                .readNBytes(Math.toIntExact(maxBytes + 1));
            return bytes.length > maxBytes
                ? null : new BoundedBodyRequest(request, bytes);
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    if (listener == null) {
                        throw new IllegalArgumentException(
                            "ReadListener is required.");
                    }
                    try {
                        if (isFinished()) {
                            listener.onAllDataRead();
                        } else {
                            listener.onDataAvailable();
                            if (isFinished()) {
                                listener.onAllDataRead();
                            }
                        }
                    } catch (IOException exception) {
                        listener.onError(exception);
                    }
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null
                ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(
                new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
