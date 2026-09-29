package io.pallet.gitintegration.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubNotFoundException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubRateLimitedException;
import io.pallet.gitintegration.github.TokenScope.Access;
import io.pallet.gitintegration.github.dto.Installation;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.TestSecrets;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class GitHubClientIntegrationTest {

    private static final String REPOSITORIES = "/installation/repositories";
    private static final AtomicLong IDS = new AtomicLong(1_000);

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) {
        GitHubApiStub.register(registry);
    }

    @Autowired
    private GitHubClient client;

    @Autowired
    private GitHubRateLimitGuard rateLimits;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JsonMapper json;

    private long installationId;

    @BeforeEach
    void setUp() {
        breakers.getAllCircuitBreakers().forEach(breaker -> breaker.reset());
        installationId = IDS.incrementAndGet();
    }

    @Test
    void getInstallationSendsTheAppJwtAndApiHeadersAndParsesTheFixture() throws Exception {
        github.stubInstallation(installationId);

        Installation installation = client.getInstallation(installationId);

        assertThat(installation.id()).isEqualTo(installationId);
        assertThat(installation.account().login()).isEqualTo("octo-org");
        assertThat(installation.account().type()).isEqualTo("Organization");
        assertThat(installation.allRepositories()).isFalse();
        assertThat(installation.suspended()).isFalse();

        LoggedRequest request = single(GitHubApiStub.installationPath(installationId));
        assertThat(request.getHeader("Accept")).isEqualTo("application/vnd.github+json");
        assertThat(request.getHeader("X-GitHub-Api-Version")).isEqualTo(GitHubHttp.API_VERSION);
        assertThat(request.getHeader("User-Agent")).isEqualTo(GitHubHttp.USER_AGENT);
        SignedJWT jwt = SignedJWT.parse(request.getHeader("Authorization").substring("Bearer ".length()));
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) TestSecrets.APP_KEY.getPublic())))
                .isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("test-client-id");
        assertThat(meters.get(GitHubHttp.CALLS)
                        .tag("endpoint", "installation.get")
                        .tag("outcome", "success")
                        .timer()
                        .count())
                .isPositive();
    }

    @Test
    void mintTokenWithAScopeSendsRepositoryIdsAndPermissions() throws Exception {
        github.stubTokenMint(installationId);

        InstallationToken token = client.mintToken(
                installationId, TokenScope.repository(42, Map.of("contents", Access.READ, "checks", Access.WRITE)));

        assertThat(token.value()).startsWith("ghs_");
        assertThat(token.expiresAt()).isAfter(Instant.now());
        JsonNode body =
                json.readTree(github.mintRequests(installationId).getFirst().getBodyAsString());
        assertThat(body.get("repository_ids").get(0).asLong()).isEqualTo(42);
        assertThat(body.get("permissions").get("contents").asString()).isEqualTo("read");
        assertThat(body.get("permissions").get("checks").asString()).isEqualTo("write");
    }

    @Test
    void mintTokenForTheWholeInstallationSendsNoBody() {
        github.stubTokenMint(installationId);

        client.mintToken(installationId, TokenScope.INSTALLATION);

        assertThat(github.mintRequests(installationId).getFirst().getBodyAsString())
                .isEmpty();
    }

    @Test
    void aTokenIsMintedOnceAndReusedAcrossInstallationCalls() {
        github.stubTokenMint(installationId);
        stubRepositories();

        repositories();
        repositories();

        assertThat(github.verifyTokenMints(installationId)).isOne();
        List<LoggedRequest> calls = github.requests(REPOSITORIES);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).getHeader("Authorization"))
                .isEqualTo(calls.get(1).getHeader("Authorization"));
    }

    @Test
    void a401OnAnInstallationTokenEvictsReMintsAndRetriesOnce() {
        github.stubTokenMint(installationId);
        github.server()
                .stubFor(get(urlPathEqualTo(REPOSITORIES))
                        .inScenario("revoked")
                        .whenScenarioStateIs(Scenario.STARTED)
                        .willReturn(aResponse().withStatus(401))
                        .willSetStateTo("fresh"));
        github.server()
                .stubFor(get(urlPathEqualTo(REPOSITORIES))
                        .inScenario("revoked")
                        .whenScenarioStateIs("fresh")
                        .willReturn(GitHubApiStub.fixture("installation-repositories.json")));

        GitHubResponse<Map> response = repositories();

        assertThat(response.body()).containsKey("repositories");
        assertThat(github.verifyTokenMints(installationId)).isEqualTo(2);
        List<LoggedRequest> calls = github.requests(REPOSITORIES);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).getHeader("Authorization"))
                .isNotEqualTo(calls.get(1).getHeader("Authorization"));
    }

    @Test
    void a5xxThenA200SucceedsAfterARetry() {
        String path = GitHubApiStub.installationPath(installationId);
        github.server()
                .stubFor(get(urlPathEqualTo(path))
                        .inScenario("flaky")
                        .whenScenarioStateIs(Scenario.STARTED)
                        .willReturn(aResponse().withStatus(503))
                        .willSetStateTo("recovered"));
        github.server()
                .stubFor(get(urlPathEqualTo(path))
                        .inScenario("flaky")
                        .whenScenarioStateIs("recovered")
                        .willReturn(GitHubApiStub.fixture("installation.json")));

        assertThat(client.getInstallation(installationId).id()).isEqualTo(installationId);
        assertThat(github.requests(path)).hasSize(2);
    }

    @Test
    void a404IsNotRetried() {
        String path = GitHubApiStub.installationPath(installationId);
        github.stubNotFound(path);

        assertThatThrownBy(() -> client.getInstallation(installationId)).isInstanceOf(GitHubNotFoundException.class);

        assertThat(github.requests(path)).hasSize(1);
    }

    @Test
    void aRateLimitIsNotRetriedAndIsRecordedForTheInstallation() {
        Instant resetAt = Instant.now().plusSeconds(600);
        github.stubTokenMint(installationId);
        github.stubRateLimited(REPOSITORIES, resetAt);

        assertThatThrownBy(this::repositories).isInstanceOfSatisfying(GitHubRateLimitedException.class, limited -> {
            assertThat(limited.resetAt()).isEqualTo(Instant.ofEpochSecond(resetAt.getEpochSecond()));
            assertThat(limited.getMessage()).doesNotContain("ghs_");
        });

        assertThat(github.requests(REPOSITORIES)).hasSize(1);
        assertThat(rateLimits.exhaustedUntil(installationId)).contains(Instant.ofEpochSecond(resetAt.getEpochSecond()));
        assertThat(rateLimits.allowBackground(installationId)).isFalse();
    }

    @SuppressWarnings("rawtypes")
    private GitHubResponse<Map> repositories() {
        return client.asInstallation(
                installationId,
                TokenScope.INSTALLATION,
                GitHubRequest.get("installation.repositories.list", Map.class, REPOSITORIES));
    }

    private void stubRepositories() {
        github.server()
                .stubFor(get(urlPathEqualTo(REPOSITORIES))
                        .willReturn(GitHubApiStub.fixture("installation-repositories.json")));
    }

    private LoggedRequest single(String path) {
        List<LoggedRequest> requests = github.requests(path);
        assertThat(requests).hasSize(1);
        return requests.getFirst();
    }
}
