package io.pallet.gitintegration.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One access line per request: method, path, query with every credential-shaped value masked, whether a bearer token
 * came with it (never the token), status and duration. The GitHub authorization callback carries {@code code} and
 * {@code state}; neither reaches a log line.
 */
public class RedactingAccessLog extends OncePerRequestFilter {

    static final String MASK = "***";

    private static final Logger access = LoggerFactory.getLogger("pallet.access");
    private static final String ACTUATOR_PREFIX = "/actuator";
    private static final String AUTHORIZATION = "Authorization";
    private static final Set<String> SECRET_PARAMETERS =
            Set.of("code", "state", "token", "access_token", "refresh_token", "client_secret");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            logAccess(request, response, started);
        }
    }

    private static void logAccess(HttpServletRequest request, HttpServletResponse response, long started) {
        String path = request.getRequestURI();
        if (!access.isInfoEnabled() || path.startsWith(request.getContextPath() + ACTUATOR_PREFIX)) {
            return;
        }
        access.info(
                "{} {}{} {} {} {}ms",
                request.getMethod(),
                clean(path),
                redactQuery(request.getQueryString()),
                request.getHeader(AUTHORIZATION) == null ? "anonymous" : "bearer",
                response.getStatus(),
                (System.nanoTime() - started) / 1_000_000);
    }

    /** @return the query with each sensitive parameter's value masked, prefixed with {@code ?}, or empty */
    static String redactQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringJoiner redacted = new StringJoiner("&", "?", "");
        for (String pair : query.split("&", -1)) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (equals >= 0 && SECRET_PARAMETERS.contains(name.toLowerCase(Locale.ROOT))) {
                redacted.add(clean(name) + "=" + MASK);
            } else {
                redacted.add(clean(pair));
            }
        }
        return redacted.toString();
    }

    private static String clean(String value) {
        return CONTROL.matcher(value).replaceAll("_");
    }
}
