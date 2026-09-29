package io.pallet.gitintegration.delivery.payload;

import java.util.List;
import java.util.Optional;

/**
 * A {@code push}. {@code before} is all zeros for a new ref and {@code after} for a deleted one. {@code headCommit} is
 * absent when the push carries no commit (a deleted branch, most tags).
 */
public record PushPayload(
        String ref,
        String before,
        String after,
        boolean created,
        boolean deleted,
        boolean forced,
        long installationId,
        long repositoryId,
        String repositoryFullName,
        long senderId,
        Optional<HeadCommit> headCommit,
        List<Commit> commits)
        implements DeliveryPayload {

    public static final String BRANCH_PREFIX = "refs/heads/";
    public static final String TAG_PREFIX = "refs/tags/";
    public static final String NULL_SHA = "0".repeat(40);

    public PushPayload {
        commits = List.copyOf(commits);
    }

    public boolean isBranch() {
        return ref.startsWith(BRANCH_PREFIX);
    }

    /** The branch name, for a {@code refs/heads/} ref. */
    public Optional<String> branch() {
        return isBranch() ? Optional.of(ref.substring(BRANCH_PREFIX.length())) : Optional.empty();
    }

    /**
     * {@code message} is the first line only, at most 256 characters; untrusted text. {@code skipMarked} is whether the
     * full message carried a configured skip marker.
     */
    public record HeadCommit(String sha, String message, String author, boolean skipMarked) {}

    public boolean skipMarked() {
        return headCommit.map(HeadCommit::skipMarked).orElse(false);
    }

    /** One commit's changed paths, for the path filter. */
    public record Commit(List<String> added, List<String> removed, List<String> modified) {

        public Commit {
            added = List.copyOf(added);
            removed = List.copyOf(removed);
            modified = List.copyOf(modified);
        }
    }
}
