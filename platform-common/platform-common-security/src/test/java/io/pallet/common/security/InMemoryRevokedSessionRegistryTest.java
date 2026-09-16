package io.pallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

@UnitTest
class InMemoryRevokedSessionRegistryTest {

    private final InMemoryRevokedSessionRegistry registry = new InMemoryRevokedSessionRegistry();

    @Test
    void aSessionThatWasNeverRevokedIsNotRevoked() {
        assertThat(registry.isRevoked("session-1")).isFalse();
    }

    @Test
    void aRevokedSessionIsRevokedUntilItsRetentionExpires() throws InterruptedException {
        registry.revoke("session-1", Duration.ofMillis(200));

        assertThat(registry.isRevoked("session-1")).isTrue();

        Thread.sleep(300);

        assertThat(registry.isRevoked("session-1")).isFalse();
    }
}
