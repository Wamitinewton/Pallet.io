package io.pallet.orgteam.observability;

import java.util.regex.Pattern;

/** The public invite preview carries a bearer capability in its path; anything that records a path uses this first. */
public final class PathRedactor {

    static final String REDACTED_SEGMENT = "{token}";

    private static final Pattern INVITE_PREVIEW =
            Pattern.compile("^(.*?/org-team/invites)/[^/]+.*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private PathRedactor() {}

    public static String redact(String path) {
        if (path == null) {
            return null;
        }
        return INVITE_PREVIEW.matcher(path).replaceFirst("$1/" + REDACTED_SEGMENT);
    }
}
