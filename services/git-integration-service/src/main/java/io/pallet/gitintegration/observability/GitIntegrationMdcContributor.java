package io.pallet.gitintegration.observability;

import io.pallet.common.observability.MdcContributor;
import io.pallet.gitintegration.webhook.WebhookHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Request-scope MDC keys, read before the request is authorized, so each value is accepted only in the shape a real id
 * has: whatever reaches the MDC reaches every log line of the request.
 */
@Component
class GitIntegrationMdcContributor implements MdcContributor {

    static final String ORG_ID = "orgId";
    static final String INSTALLATION_ID = "installationId";
    static final String DELIVERY_ID = "deliveryId";

    private static final Pattern ORG_IN_PATH = Pattern.compile("/orgs/([A-Za-z0-9_-]{1,64})(?:/|$)");
    private static final Pattern INSTALLATION_IN_PATH = Pattern.compile("/installations/([1-9][0-9]{0,18})(?:/|$)");
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

    @Override
    public Map<String, String> contribute() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return Map.of();
        }
        HttpServletRequest request = servlet.getRequest();
        Map<String, String> keys = new HashMap<>();
        String path = request.getRequestURI();
        if (path != null) {
            group(ORG_IN_PATH.matcher(path)).ifPresent(orgId -> keys.put(ORG_ID, orgId));
            group(INSTALLATION_IN_PATH.matcher(path)).ifPresent(id -> keys.put(INSTALLATION_ID, id));
        }
        String deliveryId = request.getHeader(WebhookHeaders.DELIVERY);
        if (deliveryId != null && CANONICAL_UUID.matcher(deliveryId).matches()) {
            keys.put(DELIVERY_ID, deliveryId);
        }
        return keys;
    }

    private static Optional<String> group(Matcher matcher) {
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
