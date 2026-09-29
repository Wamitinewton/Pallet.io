package io.pallet.gitintegration.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.github.dockerjava.api.DockerClient;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.assertions.PalletAssertions;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.github.GitHubUserToken;
import io.pallet.gitintegration.support.Tokens;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, Tokens.LocalDecoder.class})
class GitHubUserSessionStoreIntegrationTest {

    private static final String TOKEN = "gho_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private GitHubUserSessionStore store;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private GenericContainer<?> redisContainer;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void savesFindsAndDeletes() {
        String sub = newSub();
        long githubUserId = newGithubUserId();
        store.save(sub, session(githubUserId, Instant.now().plus(Duration.ofMinutes(30))));

        GitHubUserSession found = store.find(sub).orElseThrow();
        assertThat(found.githubUserId()).isEqualTo(githubUserId);
        assertThat(found.githubLogin()).isEqualTo("octocat");
        assertThat(found.token().value()).isEqualTo(TOKEN);
        assertThat(found.toString()).doesNotContain(TOKEN);

        store.delete(sub);

        assertThat(store.find(sub)).isEmpty();
        assertThat(redis.opsForSet().isMember(GitHubUserSessionStore.indexKey(githubUserId), sub))
                .isFalse();
    }

    @Test
    void theTtlIsTheShorterOfMaxTtlAndTheTokensExpiry() {
        String longLived = newSub();
        String shortLived = newSub();
        store.save(longLived, session(newGithubUserId(), Instant.now().plus(Duration.ofHours(8))));
        store.save(shortLived, session(newGithubUserId(), Instant.now().plus(Duration.ofMinutes(5))));

        assertThat(ttlSeconds(longLived)).isBetween(3500L, 3600L);
        assertThat(ttlSeconds(shortLived)).isBetween(250L, 300L);
    }

    @Test
    void theStoredValueIsNotThePlaintextToken() {
        String sub = newSub();
        store.save(sub, session(newGithubUserId(), Instant.now().plus(Duration.ofMinutes(30))));

        String raw = redis.opsForValue().get(GitHubUserSessionStore.sessionKey(sub));

        assertThat(raw).isNotBlank().doesNotContain(TOKEN).doesNotContain("octocat");
    }

    @Test
    void aValueCopiedUnderAnotherUsersKeyIsNoSession() {
        String owner = newSub();
        String thief = newSub();
        store.save(owner, session(newGithubUserId(), Instant.now().plus(Duration.ofMinutes(30))));
        redis.opsForValue()
                .set(
                        GitHubUserSessionStore.sessionKey(thief),
                        redis.opsForValue().get(GitHubUserSessionStore.sessionKey(owner)),
                        Duration.ofMinutes(5));

        assertThat(store.find(thief)).isEmpty();
    }

    @Test
    void theGithubUserIndexEndsEverySessionIssuedToThatUserAndNoOther() {
        long githubUserId = newGithubUserId();
        String first = newSub();
        String second = newSub();
        String switchedAccounts = newSub();
        store.save(first, session(githubUserId, Instant.now().plus(Duration.ofMinutes(30))));
        store.save(second, session(githubUserId, Instant.now().plus(Duration.ofMinutes(30))));
        store.save(switchedAccounts, session(githubUserId, Instant.now().plus(Duration.ofMinutes(30))));
        store.save(switchedAccounts, session(newGithubUserId(), Instant.now().plus(Duration.ofMinutes(30))));

        assertThat(store.findSubjectsByGithubUser(githubUserId)).containsExactlyInAnyOrder(first, second);

        store.deleteByGithubUser(githubUserId);

        assertThat(store.find(first)).isEmpty();
        assertThat(store.find(second)).isEmpty();
        assertThat(store.find(switchedAccounts)).isPresent();
        assertThat(redis.hasKey(GitHubUserSessionStore.indexKey(githubUserId))).isFalse();
    }

    @Test
    void pausedRedisFailsSessionEndpointsWhileHealthStaysUp() throws Exception {
        String bearer = "Bearer " + Tokens.forUser(newSub()).signed();
        DockerClient docker = redisContainer.getDockerClient();

        docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
        MockHttpServletResponse session;
        MockHttpServletResponse health;
        try {
            session = mvc.perform(get("/api/v1/git-integration/github/session").header("Authorization", bearer))
                    .andReturn()
                    .getResponse();
            health = mvc.perform(get("/actuator/health")).andReturn().getResponse();
        } finally {
            docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }

        assertThat(session.getStatus()).isEqualTo(502);
        PalletAssertions.assertThat(jsonMapper.readValue(session.getContentAsString(), ErrorResponse.class))
                .isFailure()
                .hasErrorCode("EXTERNAL_SERVICE_ERROR");
        assertThat(health.getStatus()).isEqualTo(200);
        assertThat(jsonMapper
                        .readTree(health.getContentAsString())
                        .path("status")
                        .asString())
                .isEqualTo("UP");
    }

    private long ttlSeconds(String sub) {
        return redis.getExpire(GitHubUserSessionStore.sessionKey(sub));
    }

    private static GitHubUserSession session(long githubUserId, Instant expiresAt) {
        return new GitHubUserSession(
                new GitHubUserToken(TOKEN), githubUserId, "octocat", expiresAt.truncatedTo(ChronoUnit.MILLIS));
    }

    private static String newSub() {
        return "user-" + UUID.randomUUID();
    }

    private static long newGithubUserId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }
}
