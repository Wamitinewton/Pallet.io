package io.pallet.gitintegration.push;

import io.pallet.gitintegration.delivery.payload.PushPayload;
import io.pallet.gitintegration.delivery.payload.PushPayload.Commit;
import java.util.List;
import java.util.stream.Stream;

/**
 * Whether a push changed anything under a monorepo app's root directory. It can only say no when the payload lists
 * every changed path; otherwise the app builds, since a wasted build costs less than a missed one.
 */
public final class PathFilter {

    /** GitHub lists at most this many commits in a push payload. */
    static final int MAX_LISTED_COMMITS = 20;

    /** A commit's file list this long may have been cut short by GitHub. */
    static final int MAX_LISTED_FILES = 3000;

    private PathFilter() {}

    /** @param rootDirectory normalized, without a trailing {@code /}; null for the repository root */
    public static boolean touches(PushPayload push, String rootDirectory) {
        if (rootDirectory == null || !listsEveryChange(push)) {
            return true;
        }
        String prefix = rootDirectory + "/";
        return push.commits().stream().flatMap(PathFilter::paths).anyMatch(path -> path.startsWith(prefix));
    }

    /** A force push or a new branch lists commits that aren't a diff against anything built. */
    static boolean listsEveryChange(PushPayload push) {
        return !push.forced()
                && !PushPayload.NULL_SHA.equals(push.before())
                && push.commits().size() < MAX_LISTED_COMMITS
                && push.commits().stream().noneMatch(PathFilter::mayBeCapped);
    }

    private static boolean mayBeCapped(Commit commit) {
        return Stream.of(commit.added(), commit.removed(), commit.modified())
                .anyMatch(files -> files.size() >= MAX_LISTED_FILES);
    }

    private static Stream<String> paths(Commit commit) {
        return Stream.of(commit.added(), commit.removed(), commit.modified()).flatMap(List::stream);
    }
}
