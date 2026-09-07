package io.pallet.common.events;

/**
 * The Pallet event catalog. Topic names follow {@code domain.fact} in the past
 * tense: a statement that something already happened, never an instruction. A
 * rollback trigger is still modelled as an event with one intended consumer
 * rather than a synchronous call, so no producer blocks waiting on a reply.
 */
public final class Topics {

    private Topics() {
    }

    public static final String GIT_PUSH_RECEIVED = "git.push.received";
    public static final String BUILD_STARTED = "build.started";
    public static final String BUILD_SUCCEEDED = "build.succeeded";
    public static final String BUILD_FAILED = "build.failed";
    public static final String DEPLOY_STEP_COMPLETED = "deploy.step.completed";
    public static final String DEPLOY_STATE_CHANGED = "deploy.state.changed";
    public static final String DEPLOY_ROLLBACK_TRIGGERED = "deploy.rollback.triggered";
    public static final String DNS_RECORD_UPDATED = "dns.record.updated";
    public static final String TLS_CERT_ISSUED = "tls.cert.issued";
    public static final String HEALTH_CHECK_FAILED = "health.check.failed";
    public static final String USAGE_RECORDED = "usage.recorded";
    public static final String BILLING_PAYMENT_RECEIVED = "billing.payment.received";
    public static final String AUDIT_EVENT_RECORDED = "audit.event.recorded";
}
