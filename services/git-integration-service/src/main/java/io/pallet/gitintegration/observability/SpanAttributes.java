package io.pallet.gitintegration.observability;

/** Ids are fine on a span, where they find one trace; they never become metric labels. */
public final class SpanAttributes {

    public static final String EVENT = "git.event";
    public static final String DELIVERY_ID = "git.delivery_id";
    public static final String INSTALLATION_ID = "git.installation_id";

    private SpanAttributes() {}
}
