package io.pallet.buildqueue.observability;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One INFO line per status change, with fields that are JSON keys under the structured encoder. Ids, statuses and
 * reason codes only: a build's commit message, author and repository are untrusted text and never reach a log.
 */
@Component
public class TransitionLog {

    private static final Logger log = LoggerFactory.getLogger(TransitionLog.class);

    /**
     * @param from the status the build left, or null for one created in {@code to}
     * @param reason the status reason code, or null when the transition has none
     */
    public void record(UUID buildId, String orgId, String appId, String from, String to, String reason, int attempt) {
        log.info(
                "Build transition {} {} {} {} {} {} {}",
                keyValue("buildId", buildId),
                keyValue("orgId", orgId),
                keyValue("appId", appId),
                keyValue("from", from),
                keyValue("to", to),
                keyValue("reason", reason),
                keyValue("attempt", attempt));
    }
}
