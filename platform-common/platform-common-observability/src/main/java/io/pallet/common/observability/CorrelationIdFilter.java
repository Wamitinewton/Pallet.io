package io.pallet.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a stable {@code X-Correlation-Id} into the MDC for the request and echoes it on the
 * response. Reuses an inbound value, generates a UUID when there is none.
 *
 * <p>{@link MdcContributor}s run at filter entry, so a key sourced from the authenticated
 * principal (such as {@code orgId}) is absent on a request that 401s before auth — which is
 * correct, there is no principal to attribute it to.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * An inbound id outside this shape is dropped: it reaches a response header and the logs.
     */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final String header;
    private final MdcContributors contributors;

    public CorrelationIdFilter(String header, List<MdcContributor> contributors) {
        this.header = header;
        this.contributors = new MdcContributors(contributors);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String inbound = request.getHeader(header);
        String correlationId = inbound != null && SAFE.matcher(inbound).matches()
            ? inbound
            : UUID.randomUUID().toString();
        response.setHeader(header, correlationId);

        MDC.put(CorrelationId.MDC_KEY, correlationId);
        List<String> contributed = contributors.apply();
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
            contributed.forEach(MDC::remove);
        }
    }
}
