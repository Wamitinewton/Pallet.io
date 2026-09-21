package io.pallet.orgteam.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Writes one access line per request and makes requests report a redacted path so error bodies and log lines that echo
 * {@code getRequestURI()} cannot carry an invite token. Only the one route that reads the token keeps the raw path
 * until routing has failed or finished, and it is also stripped of credentials: it is public, so a bad bearer token
 * must not turn it into a 401 that echoes the path.
 */
public class RedactingAccessLog extends OncePerRequestFilter {

    static final String REDACT_ATTRIBUTE = RedactingAccessLog.class.getName() + ".REDACT";

    private static final Logger access = LoggerFactory.getLogger("pallet.access");
    private static final String ACTUATOR_PREFIX = "/actuator";
    private static final String AUTHORIZATION = "Authorization";

    private final String previewPrefix;

    public RedactingAccessLog(String previewPrefix) {
        this.previewPrefix = previewPrefix;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(wrap(request), response);
        } finally {
            logAccess(request, response, started);
        }
    }

    private HttpServletRequest wrap(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (isPreviewRoute(request, uri)) {
            return new RedactingRequest(request, true);
        }
        if (!PathRedactor.redact(uri).equals(uri)) {
            request.setAttribute(REDACT_ATTRIBUTE, Boolean.TRUE);
        }
        return new RedactingRequest(request, false);
    }

    private boolean isPreviewRoute(HttpServletRequest request, String uri) {
        String prefix = request.getContextPath() + previewPrefix;
        return "GET".equals(request.getMethod())
                && uri.length() > prefix.length()
                && uri.startsWith(prefix)
                && uri.indexOf('/', prefix.length()) < 0;
    }

    private static void logAccess(HttpServletRequest request, HttpServletResponse response, long started) {
        String path = PathRedactor.redact(request.getRequestURI());
        if (!access.isInfoEnabled() || path.startsWith(request.getContextPath() + ACTUATOR_PREFIX)) {
            return;
        }
        access.info(
                "{} {} {} {}ms",
                request.getMethod(),
                path,
                response.getStatus(),
                (System.nanoTime() - started) / 1_000_000);
    }

    private static final class RedactingRequest extends HttpServletRequestWrapper {

        private final boolean anonymous;

        RedactingRequest(HttpServletRequest request, boolean anonymous) {
            super(request);
            this.anonymous = anonymous;
        }

        @Override
        public String getHeader(String name) {
            return anonymous && AUTHORIZATION.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return anonymous && AUTHORIZATION.equalsIgnoreCase(name)
                    ? Collections.emptyEnumeration()
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            if (!anonymous) {
                return super.getHeaderNames();
            }
            return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                    .filter(header -> !AUTHORIZATION.equalsIgnoreCase(header))
                    .toList());
        }

        @Override
        public String getRequestURI() {
            String uri = super.getRequestURI();
            return redacting() ? PathRedactor.redact(uri) : uri;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = super.getRequestURL();
            return redacting() ? new StringBuffer(PathRedactor.redact(url.toString())) : url;
        }

        private boolean redacting() {
            return getAttribute(REDACT_ATTRIBUTE) != null;
        }
    }
}
