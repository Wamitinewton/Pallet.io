package io.pallet.gitintegration.github;

/**
 * A classified success: the parsed body, or {@code notModified} with no body when an {@code If-None-Match} held.
 * {@code link} is the raw {@code Link} header, which only the client's paging methods read.
 */
public record GitHubResponse<T>(T body, String etag, boolean notModified, String link) {

    static <T> GitHubResponse<T> of(T body, String etag, String link) {
        return new GitHubResponse<>(body, etag, false, link);
    }

    static <T> GitHubResponse<T> unchanged(String etag) {
        return new GitHubResponse<>(null, etag, true, null);
    }
}
