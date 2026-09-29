package io.pallet.gitintegration.github;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubRateLimitedException;
import io.pallet.gitintegration.github.GitHubExceptions.WriteOutcomeUnknownException;
import io.pallet.gitintegration.github.GitHubResponseClassifier.Classification;
import io.pallet.gitintegration.github.GitHubResponseClassifier.Outcome;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes a {@link GitHubRequest} under the credential's resilience policy. The response is classified inside the
 * guarded supplier, so only 5xx, timeouts, and connection failures reach the breaker and the retry. Never logs a
 * header, a body, or a query string.
 */
class GitHubHttp implements AutoCloseable {

    static final String CALLS = "git.github.calls";
    static final String API_VERSION = "2022-11-28";
    static final String USER_AGENT = "pallet-git-integration";
    static final MediaType GITHUB_JSON = MediaType.parseMediaType("application/vnd.github+json");

    private final HttpClient httpClient;
    private final RestClient api;
    private final RestClient web;
    private final ExternalCall externalCall;
    private final GitHubResponseClassifier classifier;
    private final GitHubRateLimitGuard rateLimits;
    private final JsonMapper json;
    private final MeterRegistry meters;

    GitHubHttp(
            HttpClient httpClient,
            RestClient api,
            RestClient web,
            ExternalCall externalCall,
            GitHubResponseClassifier classifier,
            GitHubRateLimitGuard rateLimits,
            JsonMapper json,
            MeterRegistry meters) {
        this.httpClient = httpClient;
        this.api = api;
        this.web = web;
        this.externalCall = externalCall;
        this.classifier = classifier;
        this.rateLimits = rateLimits;
        this.json = json;
        this.meters = meters;
    }

    <T> GitHubResponse<T> execute(GitHubRequest<T> request, GitHubCredential credential) {
        if (request.retrySafe()) {
            return externalCall.call(credential.policy(), () -> attempt(request, credential));
        }
        AtomicBoolean sent = new AtomicBoolean();
        return externalCall.call(credential.policy(), () -> {
            if (!sent.compareAndSet(false, true)) {
                throw new WriteOutcomeUnknownException(request.endpoint());
            }
            return attempt(request, credential);
        });
    }

    @Override
    public void close() {
        httpClient.close();
    }

    private <T> GitHubResponse<T> attempt(GitHubRequest<T> request, GitHubCredential credential) {
        Timer.Sample sample = Timer.start(meters);
        Outcome outcome = Outcome.ERROR;
        try {
            Exchanged<T> exchanged = spec(request, credential)
                    .exchange((httpRequest, response) -> read(request, credential, httpRequest, response));
            outcome = exchanged.classification().outcome();
            if (exchanged.classification().isFailure()) {
                throw exchanged.classification().failure();
            }
            return exchanged.response();
        } finally {
            sample.stop(Timer.builder(CALLS)
                    .tag("endpoint", request.endpoint())
                    .tag("outcome", outcome.tag())
                    .register(meters));
        }
    }

    private RestClient.RequestBodySpec spec(GitHubRequest<?> request, GitHubCredential credential) {
        RestClient client = request.host() == GitHubRequest.Host.WEB ? web : api;
        RestClient.RequestBodySpec spec = client.method(request.method())
                .uri(request.uriTemplate(), request.uriVariables().toArray())
                .headers(headers -> {
                    headers.setAccept(List.of(
                            request.host() == GitHubRequest.Host.WEB ? MediaType.APPLICATION_JSON : GITHUB_JSON));
                    headers.set("X-GitHub-Api-Version", API_VERSION);
                    headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
                    credential.authorization().ifPresent(value -> headers.set(HttpHeaders.AUTHORIZATION, value));
                    if (request.ifNoneMatch() != null) {
                        headers.setIfNoneMatch(request.ifNoneMatch());
                    }
                });
        if (request.body() != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsBytes(request.body()));
        }
        return spec;
    }

    private <T> Exchanged<T> read(
            GitHubRequest<T> request, GitHubCredential credential, HttpRequest httpRequest, ClientHttpResponse response)
            throws IOException {
        HttpHeaders headers = response.getHeaders();
        Classification classification =
                classifier.classify(response.getStatusCode(), headers, credential, request.notFound());
        if (credential instanceof GitHubCredential.Installation installation) {
            rateLimits.record(installation.installationId(), headers);
            if (classification.failure() instanceof GitHubRateLimitedException limited) {
                rateLimits.recordExhausted(installation.installationId(), limited.resetAt());
            }
        }
        if (classification.isFailure()) {
            return new Exchanged<>(classification, null);
        }
        String etag = headers.getETag();
        if (classification.outcome() == Outcome.NOT_MODIFIED) {
            return new Exchanged<>(classification, GitHubResponse.unchanged(etag));
        }
        return new Exchanged<>(
                classification, GitHubResponse.of(body(request, response), etag, headers.getFirst(HttpHeaders.LINK)));
    }

    private <T> T body(GitHubRequest<T> request, ClientHttpResponse response) throws IOException {
        if (request.responseType() == Void.class) {
            return null;
        }
        try (InputStream body = response.getBody()) {
            return json.readValue(body, request.responseType());
        }
    }

    private record Exchanged<T>(Classification classification, GitHubResponse<T> response) {}
}
