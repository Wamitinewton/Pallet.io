package io.pallet.gitintegration.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * WireMock standing in for {@code api.github.com} and {@code github.com}. One server per test JVM, so every Spring
 * context that points at it can be cached; stubs and the request journal are reset before each test.
 *
 * <pre>
 * &#64;Import(GitHubApiStub.Properties.class)
 * &#64;RegisterExtension static final GitHubApiStub github = new GitHubApiStub();
 * </pre>
 */
public final class GitHubApiStub implements BeforeEachCallback {

    private static final String FIXTURES = "/github/api/";
    private static final String JSON = "application/json; charset=utf-8";
    private static final String TEMPLATE = "response-template";

    public static final String OAUTH_TOKEN_PATH = "/login/oauth/access_token";
    public static final String USER_PATH = "/user";
    public static final String USER_INSTALLATIONS_PATH = "/user/installations";
    public static final String INSTALLATION_REPOSITORIES_PATH = "/installation/repositories";
    public static final String HOOK_DELIVERIES_PATH = "/app/hook/deliveries";
    public static final String META_PATH = "/meta";
    public static final int PAGE_SIZE = 100;

    /** One entry of the app's webhook delivery log. */
    public record LoggedDelivery(long id, String guid, Instant deliveredAt, String status, String event) {

        public static LoggedDelivery failed(long id, String guid, Instant deliveredAt, String event) {
            return new LoggedDelivery(id, guid, deliveredAt, "Invalid HTTP Response: 503", event);
        }

        public static LoggedDelivery ok(long id, String guid, Instant deliveredAt, String event) {
            return new LoggedDelivery(id, guid, deliveredAt, "OK", event);
        }

        String json() {
            return "{\"id\":" + id + ",\"guid\":\"" + guid + "\",\"delivered_at\":\"" + deliveredAt
                    + "\",\"redelivery\":false,\"duration\":0.1,\"status\":\"" + status + "\","
                    + "\"status_code\":" + ("OK".equals(status) ? 202 : 503) + ",\"event\":\"" + event + "\","
                    + "\"action\":null,\"installation_id\":1,\"repository_id\":1}";
        }
    }

    /** Actions run once, on WireMock's serving thread, after a request to their path matched and before it's answered. */
    private static final class ServeHooks implements ServeEventListener {

        private final Map<String, Runnable> byPath = new ConcurrentHashMap<>();

        @Override
        public String getName() {
            return "serve-hooks";
        }

        @Override
        public void beforeResponseSent(ServeEvent serveEvent, Parameters parameters) {
            Runnable action =
                    byPath.remove(URI.create(serveEvent.getRequest().getUrl()).getRawPath());
            if (action != null) {
                action.run();
            }
        }
    }

    private static final class Server {
        private static final ServeHooks HOOKS = new ServeHooks();
        private static final WireMockServer INSTANCE = start();

        private static WireMockServer start() {
            WireMockServer server = new WireMockServer(WireMockConfiguration.options()
                    .dynamicPort()
                    .gzipDisabled(true)
                    .globalTemplating(false)
                    .templatingEnabled(true)
                    .extensions(HOOKS));
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            return server;
        }
    }

    /**
     * Points the GitHub base URLs at the stub. Imported rather than declared per class with
     * {@code @DynamicPropertySource}, so test classes with the same setup share one cached context.
     */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Properties {

        @Bean
        static DynamicPropertyRegistrar gitHubApiStubProperties() {
            return GitHubApiStub::register;
        }
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("pallet.git.github.api-base-url", GitHubApiStub::baseUrl);
        registry.add("pallet.git.github.web-base-url", GitHubApiStub::baseUrl);
    }

    public static String baseUrl() {
        return Server.INSTANCE.baseUrl();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        Server.INSTANCE.resetAll();
        Server.HOOKS.byPath.clear();
    }

    /** Runs {@code action} once, just before GitHub's answer to the next request for {@code path} goes back. */
    public void beforeAnswering(String path, Runnable action) {
        Server.HOOKS.byPath.put(path, action);
    }

    public WireMockServer server() {
        return Server.INSTANCE;
    }

    public void stubInstallation(long installationId) {
        Server.INSTANCE.stubFor(
                get(urlPathEqualTo(installationPath(installationId))).willReturn(fixture("installation.json")));
    }

    public void stubTokenMint(long installationId) {
        Server.INSTANCE.stubFor(post(urlPathEqualTo(tokenPath(installationId)))
                .willReturn(fixture("installation-token.json").withStatus(201)));
    }

    /** {@code DELETE /app/installations/{id}}: {@code 204} uninstalls, {@code 404} has none, a 5xx is failing. */
    public void stubUninstall(long installationId, int status) {
        Server.INSTANCE.stubFor(delete(urlPathEqualTo(installationPath(installationId)))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", JSON)
                        .withBody(status == 204 ? "" : "{\"message\":\"fixture\"}")));
    }

    public List<LoggedRequest> uninstallRequests(long installationId) {
        return Server.INSTANCE.findAll(deleteRequestedFor(urlPathEqualTo(installationPath(installationId))));
    }

    public void stubCodeExchange() {
        Server.INSTANCE.stubFor(post(urlPathEqualTo(OAUTH_TOKEN_PATH)).willReturn(fixture("oauth-access-token.json")));
    }

    /** GitHub answers a bad code with {@code 200} and an {@code error} field, not a 4xx. */
    public void stubCodeRejected() {
        Server.INSTANCE.stubFor(
                post(urlPathEqualTo(OAUTH_TOKEN_PATH)).willReturn(fixture("oauth-bad-verification-code.json")));
    }

    public void stubCurrentUser() {
        Server.INSTANCE.stubFor(get(urlPathEqualTo(USER_PATH)).willReturn(fixture("user.json")));
    }

    public void stubUserInstallations() {
        Server.INSTANCE.stubFor(
                get(urlPathEqualTo(USER_INSTALLATIONS_PATH)).willReturn(fixture("user-installations.json")));
    }

    public void stubSuspendedInstallation(long installationId) {
        Server.INSTANCE.stubFor(get(urlPathEqualTo(installationPath(installationId)))
                .willReturn(fixture("installation-suspended.json")));
    }

    /**
     * {@code GET /user/installations}, one page per list of ids, answered by its {@code page} query parameter; the
     * {@code total_count} is every id across the pages. A full page has {@value #PAGE_SIZE} entries, so the caller's
     * walk only stops early on the last page.
     */
    public void stubUserInstallationPages(List<List<Long>> pages) {
        int total = pages.stream().mapToInt(List::size).sum();
        for (int i = 0; i < pages.size(); i++) {
            String installations = pages.get(i).stream()
                    .map(id -> "{\"id\":" + id + ",\"account\":{\"login\":\"octo-org\",\"id\":5001,"
                            + "\"type\":\"Organization\"},\"repository_selection\":\"selected\",\"suspended_at\":null}")
                    .collect(Collectors.joining(","));
            Server.INSTANCE.stubFor(get(urlPathEqualTo(USER_INSTALLATIONS_PATH))
                    .withQueryParam("page", equalTo(String.valueOf(i + 1)))
                    .willReturn(json("{\"total_count\":" + total + ",\"installations\":[" + installations + "]}")));
        }
    }

    /** {@value #PAGE_SIZE} other installations, so a page is full and the walk goes on to the next one. */
    public static List<Long> fullPageOfOthers(long firstId) {
        return LongStream.range(firstId, firstId + PAGE_SIZE).boxed().toList();
    }

    public void stubUserInstallationRepositories(long installationId) {
        Server.INSTANCE.stubFor(get(urlPathEqualTo(userInstallationRepositoriesPath(installationId)))
                .willReturn(fixture("user-installation-repositories.json")));
    }

    public void stubInstallationRepositories() {
        Server.INSTANCE.stubFor(get(urlPathEqualTo(INSTALLATION_REPOSITORIES_PATH))
                .willReturn(fixture("installation-repositories.json")));
    }

    /**
     * {@code GET /repositories/{id}} as a user token sees it. {@code permission} is the user's highest: {@code admin},
     * {@code maintain}, {@code push}, {@code triage}, or {@code pull}; the lower flags are set with it, as GitHub does.
     */
    public void stubRepository(long repoId, long ownerId, String permission, boolean archived) {
        List<String> order = List.of("pull", "triage", "push", "maintain", "admin");
        int held = order.indexOf(permission);
        if (held < 0) {
            throw new IllegalArgumentException("Unknown permission " + permission);
        }
        String permissions = order.stream()
                .map(level -> "\"" + level + "\":" + (order.indexOf(level) <= held))
                .collect(Collectors.joining(","));
        Server.INSTANCE.stubFor(get(urlPathEqualTo(repositoryPath(repoId)))
                .willReturn(json("{\"id\":" + repoId + ",\"full_name\":\"octo-org/repo-" + repoId + "\","
                        + "\"default_branch\":\"main\",\"private\":true,\"archived\":" + archived + ","
                        + "\"owner\":{\"id\":" + ownerId + ",\"login\":\"octo-org\"},"
                        + "\"permissions\":{" + permissions + "}}")));
    }

    public void stubBranch(long repoId, String branch, String sha) {
        Server.INSTANCE.stubFor(get(urlPathEqualTo(branchPath(repoId, branch)))
                .willReturn(json("{\"name\":\"" + branch + "\",\"commit\":{\"sha\":\"" + sha + "\"}}")));
    }

    /** {@code status} is {@code ahead}, {@code behind}, {@code identical}, or {@code diverged}. */
    public void stubCompare(long repoId, String base, String head, String status) {
        Server.INSTANCE.stubFor(get(urlPathEqualTo(comparePath(repoId, base, head)))
                .willReturn(json("{\"status\":\"" + status + "\",\"ahead_by\":1,\"behind_by\":0,"
                        + "\"total_commits\":1,\"commits\":[],\"files\":[]}")));
    }

    public void stubCommit(long repoId, String sha) {
        Server.INSTANCE.stubFor(get(urlPathEqualTo(commitPath(repoId, sha)))
                .willReturn(json("{\"sha\":\"" + sha + "\",\"commit\":{\"message\":\"fixture\"},\"files\":[]}")));
    }

    /** GitHub's answer to a mint naming a repository outside the installation's selection. */
    public void stubMintRefused(long installationId) {
        Server.INSTANCE.stubFor(post(urlPathEqualTo(tokenPath(installationId)))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", JSON)
                        .withBody("{\"message\":\"There is at least one repository that does not exist or is not"
                                + " accessible to the parent installation.\"}")));
    }

    /** {@code GET /app/hook/deliveries} as one page, newest first as GitHub lists it. */
    public void stubHookDeliveries(List<LoggedDelivery> newestFirst) {
        stubHookDeliveryPages(List.of(newestFirst));
    }

    /** One page per list; each page but the last links to the next through a {@code cursor} of {@code page-<n>}. */
    public void stubHookDeliveryPages(List<List<LoggedDelivery>> pages) {
        for (int i = 0; i < pages.size(); i++) {
            ResponseDefinitionBuilder page =
                    json(pages.get(i).stream().map(LoggedDelivery::json).collect(Collectors.joining(",", "[", "]")));
            if (i + 1 < pages.size()) {
                page.withHeader(
                        "Link",
                        "<" + baseUrl() + HOOK_DELIVERIES_PATH + "?per_page=100&cursor=page-" + (i + 1)
                                + ">; rel=\"next\"");
            }
            Server.INSTANCE.stubFor(get(urlPathEqualTo(HOOK_DELIVERIES_PATH))
                    .withQueryParam("cursor", i == 0 ? absent() : equalTo("page-" + i))
                    .willReturn(page));
        }
    }

    /** GitHub accepts every redelivery request with {@code 202}. */
    public void stubRedeliveriesAccepted() {
        Server.INSTANCE.stubFor(post(urlPathMatching(HOOK_DELIVERIES_PATH + "/[0-9]+/attempts"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", JSON)
                        .withBody("{}")));
    }

    /** The ids of every redelivery requested, in request order. */
    public List<Long> redeliveryRequests() {
        return Server.INSTANCE
                .findAll(postRequestedFor(urlPathMatching(HOOK_DELIVERIES_PATH + "/[0-9]+/attempts")))
                .stream()
                .sorted(Comparator.comparing(LoggedRequest::getLoggedDate))
                .map(request ->
                        Long.parseLong(URI.create(request.getUrl()).getPath().split("/")[4]))
                .toList();
    }

    public static String redeliveryPath(long hookDeliveryId) {
        return HOOK_DELIVERIES_PATH + "/" + hookDeliveryId + "/attempts";
    }

    /** The branch at {@code sha}, answered {@code 304} to a read conditional on {@code etag}. */
    public void stubBranch(long repoId, String branch, String sha, String etag) {
        Server.INSTANCE.stubFor(get(urlPathEqualTo(branchPath(repoId, branch)))
                .atPriority(1)
                .withHeader("If-None-Match", equalTo(etag))
                .willReturn(aResponse().withStatus(304).withHeader("ETag", etag)));
        Server.INSTANCE.stubFor(get(urlPathEqualTo(branchPath(repoId, branch)))
                .atPriority(2)
                .willReturn(json("{\"name\":\"" + branch + "\",\"commit\":{\"sha\":\"" + sha + "\"}}")
                        .withHeader("ETag", etag)));
    }

    /**
     * {@code GET /repositories/{id}/collaborators/{login}/permission} for the account {@code userId}. {@code roleName}
     * is GitHub's role ({@code admin}, {@code maintain}, {@code write}, {@code triage}, {@code read}, or a custom one)
     * and {@code permission} its legacy base ({@code admin}, {@code write}, {@code read}, {@code none}).
     */
    public void stubCollaboratorPermission(long repoId, String login, long userId, String roleName, String permission) {
        Server.INSTANCE.stubFor(get(urlPathEqualTo(collaboratorPermissionPath(repoId, login)))
                .willReturn(json("{\"permission\":\"" + permission + "\",\"role_name\":\"" + roleName + "\","
                        + "\"user\":{\"login\":\"" + login + "\",\"id\":" + userId + "}}")));
    }

    /** {@code GET /user/{id}}: the account's current login. */
    public void stubUserById(long userId, String login) {
        Server.INSTANCE.stubFor(get(urlPathEqualTo(userByIdPath(userId)))
                .willReturn(json("{\"login\":\"" + login + "\",\"id\":" + userId + ",\"type\":\"User\"}")));
    }

    /** {@code POST /repositories/{id}/check-runs} creates {@code checkRunId}, answering after {@code delayMillis}. */
    public void stubCheckRunCreated(long repoId, long checkRunId, int delayMillis) {
        Server.INSTANCE.stubFor(post(urlPathEqualTo(checkRunsPath(repoId)))
                .willReturn(json("{\"id\":" + checkRunId + ",\"name\":\"fixture\"}")
                        .withStatus(201)
                        .withFixedDelay(delayMillis)));
    }

    public void stubCheckRunUpdated(long repoId, long checkRunId) {
        Server.INSTANCE.stubFor(patch(urlPathEqualTo(checkRunPath(repoId, checkRunId)))
                .willReturn(json("{\"id\":" + checkRunId + ",\"name\":\"fixture\"}")));
    }

    /** {@code GET /repositories/{id}/commits/{sha}/check-runs}, listing {@code checkRunsJson} (a JSON array). */
    public void stubCheckRunsForCommit(long repoId, String sha, String checkRunsJson) {
        Server.INSTANCE.stubFor(get(urlPathEqualTo(commitCheckRunsPath(repoId, sha)))
                .willReturn(json("{\"total_count\":1,\"check_runs\":" + checkRunsJson + "}")));
    }

    public static String checkRunsPath(long repoId) {
        return "/repositories/" + repoId + "/check-runs";
    }

    public static String checkRunPath(long repoId, long checkRunId) {
        return checkRunsPath(repoId) + "/" + checkRunId;
    }

    public static String commitCheckRunsPath(long repoId, String sha) {
        return "/repositories/" + repoId + "/commits/" + sha + "/check-runs";
    }

    public static String collaboratorPermissionPath(long repoId, String login) {
        return repositoryPath(repoId) + "/collaborators/" + login + "/permission";
    }

    public static String userByIdPath(long userId) {
        return USER_PATH + "/" + userId;
    }

    public static String repositoryPath(long repoId) {
        return "/repositories/" + repoId;
    }

    public static String comparePath(long repoId, String base, String head) {
        return repositoryPath(repoId) + "/compare/" + base + "..." + head;
    }

    public static String commitPath(long repoId, String sha) {
        return repositoryPath(repoId) + "/commits/" + sha;
    }

    public static String branchPath(long repoId, String branch) {
        return repositoryPath(repoId) + "/branches/" + branch;
    }

    public static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", JSON).withBody(body);
    }

    public static String userInstallationRepositoriesPath(long installationId) {
        return USER_INSTALLATIONS_PATH + "/" + installationId + "/repositories";
    }

    public void stubMeta(List<String> hooks) {
        String ranges = hooks.stream().map(range -> "\"" + range + "\"").collect(Collectors.joining(","));
        Server.INSTANCE.stubFor(get(urlPathEqualTo(META_PATH))
                .willReturn(json("{\"verifiable_password_authentication\":false,\"hooks\":[" + ranges
                        + "],\"web\":[\"140.82.112.0/20\"]}")));
    }

    public void stubUnauthorized(String path) {
        Server.INSTANCE.stubFor(any(urlPathEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", JSON)
                        .withBody("{\"message\":\"Bad credentials\"}")));
    }

    public List<LoggedRequest> allRequests() {
        return Server.INSTANCE.findAll(anyRequestedFor(anyUrl()));
    }

    public void stubRateLimited(String path, Instant resetAt) {
        Server.INSTANCE.stubFor(any(urlPathEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", JSON)
                        .withHeader("x-ratelimit-limit", "5000")
                        .withHeader("x-ratelimit-remaining", "0")
                        .withHeader("x-ratelimit-reset", String.valueOf(resetAt.getEpochSecond()))
                        .withBody("{\"message\":\"API rate limit exceeded\"}")));
    }

    public void stub5xx(String path) {
        Server.INSTANCE.stubFor(
                any(urlPathEqualTo(path)).willReturn(aResponse().withStatus(502).withBody("<html>Bad gateway</html>")));
    }

    public void stubNotFound(String path) {
        Server.INSTANCE.stubFor(any(urlPathEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", JSON)
                        .withBody("{\"message\":\"Not Found\"}")));
    }

    public int verifyTokenMints(long installationId) {
        return mintRequests(installationId).size();
    }

    public List<LoggedRequest> mintRequests(long installationId) {
        return Server.INSTANCE.findAll(postRequestedFor(urlPathEqualTo(tokenPath(installationId))));
    }

    public List<LoggedRequest> requests(String path) {
        return Server.INSTANCE.findAll(anyRequestedFor(urlPathEqualTo(path)));
    }

    public int totalRequests() {
        return Server.INSTANCE.getAllServeEvents().size();
    }

    public static String installationPath(long installationId) {
        return "/app/installations/" + installationId;
    }

    public static String tokenPath(long installationId) {
        return installationPath(installationId) + "/access_tokens";
    }

    public static ResponseDefinitionBuilder fixture(String name) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", JSON)
                .withBody(read(name))
                .withTransformers(TEMPLATE);
    }

    private static String read(String name) {
        try (InputStream in = GitHubApiStub.class.getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + FIXTURES + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
