package io.pallet.gitintegration.github;

import io.pallet.common.error.AppException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubNotFoundException;
import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * One call to GitHub. {@code endpoint} is a fixed, low-cardinality name ({@code installation.get}) used as the metric
 * tag; it never carries an id. A request that is not {@code retrySafe} is sent at most once per call: a write GitHub
 * may have applied before the answer was lost must not be applied twice.
 */
record GitHubRequest<T>(
        String endpoint,
        Host host,
        HttpMethod method,
        String uriTemplate,
        List<Object> uriVariables,
        Object body,
        Class<T> responseType,
        String ifNoneMatch,
        NotFoundMapper notFound,
        boolean retrySafe) {

    enum Host {
        API,
        WEB
    }

    /** Maps a 404, 410, or 422 to the caller's domain exception. */
    @FunctionalInterface
    interface NotFoundMapper {

        NotFoundMapper DEFAULT = GitHubNotFoundException::new;

        AppException map(int status);
    }

    GitHubRequest {
        uriVariables = List.copyOf(uriVariables);
    }

    static <T> GitHubRequest<T> get(String endpoint, Class<T> responseType, String uriTemplate, Object... variables) {
        return of(endpoint, HttpMethod.GET, responseType, uriTemplate, variables);
    }

    static <T> GitHubRequest<T> post(String endpoint, Class<T> responseType, String uriTemplate, Object... variables) {
        return of(endpoint, HttpMethod.POST, responseType, uriTemplate, variables);
    }

    static <T> GitHubRequest<T> patch(String endpoint, Class<T> responseType, String uriTemplate, Object... variables) {
        return of(endpoint, HttpMethod.PATCH, responseType, uriTemplate, variables);
    }

    static <T> GitHubRequest<T> delete(
            String endpoint, Class<T> responseType, String uriTemplate, Object... variables) {
        return of(endpoint, HttpMethod.DELETE, responseType, uriTemplate, variables);
    }

    private static <T> GitHubRequest<T> of(
            String endpoint, HttpMethod method, Class<T> responseType, String uriTemplate, Object... variables) {
        return new GitHubRequest<>(
                endpoint,
                Host.API,
                method,
                uriTemplate,
                List.of(variables),
                null,
                responseType,
                null,
                NotFoundMapper.DEFAULT,
                true);
    }

    GitHubRequest<T> withBody(Object requestBody) {
        return new GitHubRequest<>(
                endpoint,
                host,
                method,
                uriTemplate,
                uriVariables,
                requestBody,
                responseType,
                ifNoneMatch,
                notFound,
                retrySafe);
    }

    GitHubRequest<T> ifNoneMatch(String etag) {
        return new GitHubRequest<>(
                endpoint, host, method, uriTemplate, uriVariables, body, responseType, etag, notFound, retrySafe);
    }

    GitHubRequest<T> onNotFound(NotFoundMapper mapper) {
        return new GitHubRequest<>(
                endpoint, host, method, uriTemplate, uriVariables, body, responseType, ifNoneMatch, mapper, retrySafe);
    }

    GitHubRequest<T> onWeb() {
        return new GitHubRequest<>(
                endpoint,
                Host.WEB,
                method,
                uriTemplate,
                uriVariables,
                body,
                responseType,
                ifNoneMatch,
                notFound,
                retrySafe);
    }

    GitHubRequest<T> sentAtMostOnce() {
        return new GitHubRequest<>(
                endpoint, host, method, uriTemplate, uriVariables, body, responseType, ifNoneMatch, notFound, false);
    }
}
