package io.pallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnectionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(classes = RedisRevokedSessionRegistryIntegrationTest.TestApp.class)
@Import(RedisTestContainerConfiguration.class)
class RedisRevokedSessionRegistryIntegrationTest {

    @Autowired
    private RevokedSessionRegistry registry;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void aSessionThatWasNeverRevokedIsNotRevoked() {
        assertThat(registry.isRevoked("session-untouched")).isFalse();
    }

    @Test
    void aRevokedSessionIsVisibleToEveryInstanceSharingTheStore() {
        registry.revoke("session-1", Duration.ofMinutes(1));

        assertThat(registry.isRevoked("session-1")).isTrue();
        RevokedSessionRegistry secondInstance =
                new RedisRevokedSessionRegistry(redisTemplate, new SimpleMeterRegistry());
        assertThat(secondInstance.isRevoked("session-1")).isTrue();
    }

    @Test
    void theRevocationMarkerCarriesATtl() {
        registry.revoke("session-ttl", Duration.ofMinutes(5));

        Long ttl = redisTemplate.getExpire("pallet:session:revoked:session-ttl");
        assertThat(ttl).isBetween(1L, 300L);
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({DataRedisAutoConfiguration.class, ServiceConnectionAutoConfiguration.class})
    static class TestApp {

        @Bean
        RevokedSessionRegistry revokedSessionRegistry(StringRedisTemplate redisTemplate) {
            return new RedisRevokedSessionRegistry(redisTemplate, new SimpleMeterRegistry());
        }
    }
}
