package io.pallet.common.events;

import java.util.Set;

/**
 * The Pallet event catalog. Topic names follow {@code domain.fact} in the past
 * tense: a statement that something already happened, never an instruction. A
 * rollback trigger is still modelled as an event with one intended consumer
 * rather than a synchronous call, so no producer blocks waiting on a reply.
 *
 * <p>In this catalog the topic name equals the event type for every entry, so a
 * publisher can use {@link PlatformEvent#eventType()} as the topic directly (see
 * {@link EventType#topicFor}).
 */
public final class Topics {

    /**
     * Suffix of the dead-letter topic paired with every catalog topic.
     */
    public static final String DLT_SUFFIX = ".DLT";
    // Command-style: one intended consumer, same envelope and delivery as a fact.
    public static final String NOTIFICATION_REQUESTED = "notification.requested";
    public static final String DEPLOY_ROLLBACK_TRIGGERED = "deploy.rollback.triggered";
    // Facts.
    public static final String GIT_PUSH_RECEIVED = "git.push.received";
    public static final String BUILD_STARTED = "build.started";
    public static final String BUILD_SUCCEEDED = "build.succeeded";
    public static final String BUILD_FAILED = "build.failed";
    public static final String DEPLOY_STEP_COMPLETED = "deploy.step.completed";
    public static final String DEPLOY_STATE_CHANGED = "deploy.state.changed";
    public static final String DNS_RECORD_UPDATED = "dns.record.updated";
    public static final String TLS_CERT_ISSUED = "tls.cert.issued";
    public static final String HEALTH_CHECK_FAILED = "health.check.failed";
    public static final String USAGE_RECORDED = "usage.recorded";
    public static final String BILLING_PAYMENT_RECEIVED = "billing.payment.received";
    public static final String AUDIT_EVENT_RECORDED = "audit.event.recorded";
    public static final String ORG_PROVISIONED = "org.provisioned";
    private static final Set<String> ALL = Set.of(
            NOTIFICATION_REQUESTED,
            DEPLOY_ROLLBACK_TRIGGERED,
            GIT_PUSH_RECEIVED,
            BUILD_STARTED,
            BUILD_SUCCEEDED,
            BUILD_FAILED,
            DEPLOY_STEP_COMPLETED,
            DEPLOY_STATE_CHANGED,
            DNS_RECORD_UPDATED,
            TLS_CERT_ISSUED,
            HEALTH_CHECK_FAILED,
            USAGE_RECORDED,
            BILLING_PAYMENT_RECEIVED,
            AUDIT_EVENT_RECORDED,
            ORG_PROVISIONED);

    private Topics() {}

    /**
     * The dead-letter topic a message lands on after retries are exhausted or it fails to deserialize.
     */
    public static String deadLetter(String topic) {
        return topic + DLT_SUFFIX;
    }

    /**
     * Every topic this catalog knows — feeds topic auto-creation (each entry plus its {@link #deadLetter}).
     */
    public static Set<String> all() {
        return ALL;
    }
}
