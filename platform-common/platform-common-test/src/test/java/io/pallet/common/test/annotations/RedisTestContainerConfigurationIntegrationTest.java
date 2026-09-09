package io.pallet.common.test.annotations;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Proves {@code RedisTestContainerConfiguration} resolves a real, reachable Redis via
 * {@code @ServiceConnection} with zero manual property wiring in the test itself, the same
 * contract {@code IntegrationTestAnnotationIntegrationTest} proves for Postgres/Kafka.
 * {@code spring-boot-starter-data-redis} being on this module's test classpath is what makes
 * {@code StringRedisTemplate} autoconfigure here with no extra {@code @ImportAutoConfiguration},
 * exactly as {@code spring-boot-starter-kafka} does for {@code KafkaAdmin} in the sibling test.
 *
 * <p>Layered on top of {@code @IntegrationTest} to prove the two compose: a service testing
 * Redis-backed code alongside its usual Postgres/Kafka context imports this configuration rather
 * than switching to a different annotation.
 */
@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class RedisTestContainerConfigurationIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void redisTemplateResolvesAndReachesTheRealRedisContainer() {
        redisTemplate.opsForValue().set("platform-common-test:probe", "reachable");

        assertThat(redisTemplate.opsForValue().get("platform-common-test:probe"))
                .isEqualTo("reachable");
    }
}
