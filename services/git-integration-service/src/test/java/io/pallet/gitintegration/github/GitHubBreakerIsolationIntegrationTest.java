package io.pallet.gitintegration.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.resilience.ResilienceRegistries;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubNotFoundException;
import io.pallet.gitintegration.github.GitHubExceptions.GitHubRateLimitedException;
import io.pallet.gitintegration.support.GitHubApiStub;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** One tenant's rate limit or missing resource must never open the breaker every tenant shares. */
@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class GitHubBreakerIsolationIntegrationTest {

    private static final long RATE_LIMITED = 2_001;
    private static final long MISSING = 2_002;
    private static final long HEALTHY = 2_003;
    private static final long FAILING = 2_004;
    private static final int CALLS = 50;

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) {
        GitHubApiStub.register(registry);
    }

    @Autowired
    private GitHubClient client;

    @Autowired
    private ResilienceRegistries registries;

    @Autowired
    private MeterRegistry meters;

    private CircuitBreaker api;
    private CircuitBreaker user;

    @BeforeEach
    void setUp() {
        api = registries.circuitBreaker(GitHubCredential.API_POLICY);
        user = registries.circuitBreaker(GitHubCredential.USER_POLICY);
        api.reset();
        user.reset();
    }

    @AfterEach
    void tearDown() {
        api.reset();
        user.reset();
    }

    @Test
    void bothBreakersAreRegisteredAtStartupSoTheirMetricsExistBeforeTheFirstCall() {
        assertThat(meters.find("resilience4j.circuitbreaker.state")
                        .tag("name", GitHubCredential.API_POLICY)
                        .gauges())
                .isNotEmpty();
        assertThat(meters.find("resilience4j.circuitbreaker.state")
                        .tag("name", GitHubCredential.USER_POLICY)
                        .gauges())
                .isNotEmpty();
    }

    @Test
    void rateLimitsAndMissingResourcesLeaveTheBreakerClosedAndOnlyOutagesOpenIt() {
        github.stubRateLimited(
                GitHubApiStub.installationPath(RATE_LIMITED), Instant.now().plusSeconds(600));
        github.stubNotFound(GitHubApiStub.installationPath(MISSING));
        github.stubInstallation(HEALTHY);
        github.stub5xx(GitHubApiStub.installationPath(FAILING));
        assertThat(api.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(20);
        assertThat(api.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(10);

        for (int i = 0; i < CALLS; i++) {
            assertThatThrownBy(() -> client.getInstallation(RATE_LIMITED))
                    .isInstanceOf(GitHubRateLimitedException.class);
            assertThatThrownBy(() -> client.getInstallation(MISSING)).isInstanceOf(GitHubNotFoundException.class);
        }

        assertThat(github.requests(GitHubApiStub.installationPath(RATE_LIMITED)))
                .hasSize(CALLS);
        assertThat(github.requests(GitHubApiStub.installationPath(MISSING))).hasSize(CALLS);
        assertThat(api.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(client.getInstallation(HEALTHY).id()).isEqualTo(HEALTHY);

        for (int i = 0; i < CALLS && api.getState() == CircuitBreaker.State.CLOSED; i++) {
            assertThatThrownBy(() -> client.getInstallation(FAILING)).isInstanceOf(ExternalServiceException.class);
        }
        assertThat(api.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int before = github.totalRequests();
        assertThatThrownBy(() -> client.getInstallation(HEALTHY)).isInstanceOf(ExternalServiceException.class);
        assertThat(github.totalRequests()).isEqualTo(before);

        assertThat(user.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
