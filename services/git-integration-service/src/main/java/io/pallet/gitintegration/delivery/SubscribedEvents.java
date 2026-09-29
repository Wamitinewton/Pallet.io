package io.pallet.gitintegration.delivery;

import java.util.Set;

/** The webhook events the Pallet GitHub App subscribes to. Anything else is stored {@code IGNORED}. */
public final class SubscribedEvents {

    public static final String PUSH = "push";
    public static final String INSTALLATION = "installation";
    public static final String INSTALLATION_REPOSITORIES = "installation_repositories";
    public static final String REPOSITORY = "repository";
    public static final String GITHUB_APP_AUTHORIZATION = "github_app_authorization";

    /** The metric label for every event outside the set, so a sender can't mint label values. */
    public static final String OTHER = "other";

    private static final Set<String> EVENTS =
            Set.of(PUSH, INSTALLATION, INSTALLATION_REPOSITORIES, REPOSITORY, GITHUB_APP_AUTHORIZATION);

    private SubscribedEvents() {}

    public static boolean contains(String event) {
        return event != null && EVENTS.contains(event);
    }

    public static String metricLabel(String event) {
        return contains(event) ? event : OTHER;
    }
}
