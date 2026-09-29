package io.pallet.gitintegration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.security.AccessExceptions.InvalidAuthorizationStateException;
import io.pallet.gitintegration.security.AuthorizationStateTokens.IssuedState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class AuthorizationStateIntegrationTest {

    private static final int CONCURRENT_CONSUMERS = 20;

    @Autowired
    private AuthorizationStateTokens tokens;

    @Autowired
    private JdbcTemplate jdbc;

    private final String userId = "user-" + UUID.randomUUID();

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM git_integration.authorization_states WHERE user_id = ?", userId);
    }

    @Test
    void aStateConsumesOnce() {
        String orgId = "org-" + UUID.randomUUID();
        IssuedState issued = tokens.issue(AuthorizationPurpose.INSTALL, userId, orgId);

        assertThat(tokens.consume(issued.state(), AuthorizationPurpose.INSTALL, userId, orgId)
                        .userId())
                .isEqualTo(userId);
        assertThatThrownBy(() -> tokens.consume(issued.state(), AuthorizationPurpose.INSTALL, userId, orgId))
                .isInstanceOf(InvalidAuthorizationStateException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT consumed_at IS NOT NULL FROM git_integration.authorization_states WHERE user_id = ?",
                        Boolean.class,
                        userId))
                .isTrue();
    }

    @Test
    void concurrentConsumesOfOneStateSucceedExactlyOnce() throws Exception {
        String state =
                tokens.issue(AuthorizationPurpose.AUTHORIZE, userId, null).state();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CONSUMERS)) {
            for (int i = 0; i < CONCURRENT_CONSUMERS; i++) {
                results.add(pool.submit((Callable<Boolean>) () -> {
                    start.await();
                    try {
                        tokens.consume(state, AuthorizationPurpose.AUTHORIZE, userId, null);
                        return true;
                    } catch (InvalidAuthorizationStateException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) {
                successes += result.get() ? 1 : 0;
            }
            assertThat(successes).isOne();
        }
    }

    @Test
    void anExpiredRowCannotBeConsumedEvenWithAValidSignature() {
        String state =
                tokens.issue(AuthorizationPurpose.AUTHORIZE, userId, null).state();
        jdbc.update(
                "UPDATE git_integration.authorization_states SET expires_at = now() - interval '1 second'"
                        + " WHERE user_id = ?",
                userId);

        assertThatThrownBy(() -> tokens.consume(state, AuthorizationPurpose.AUTHORIZE, userId, null))
                .isInstanceOf(InvalidAuthorizationStateException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT consumed_at IS NULL FROM git_integration.authorization_states WHERE user_id = ?",
                        Boolean.class,
                        userId))
                .isTrue();
    }
}
