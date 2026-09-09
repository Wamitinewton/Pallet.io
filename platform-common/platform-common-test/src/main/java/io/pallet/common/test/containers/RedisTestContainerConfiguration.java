package io.pallet.common.test.containers;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/**
 * Hands {@link RedisContainerHolder}'s already-running singleton to Spring via
 * {@code @ServiceConnection}, which binds {@code spring.data.redis.*} itself. See
 * {@link PostgresTestContainerConfiguration} for why returning the holder's static instance is
 * what keeps the container a singleton.
 *
 * <p>Not bundled into {@code @RepositoryTest} or {@code @IntegrationTest}: unlike Postgres and
 * Kafka, not every service touches Redis (only one that opts into
 * {@code RedisEventIdempotencyGuard} or Redis-backed caching does), so a test that needs it
 * imports this configuration directly:
 *
 * <pre>{@code
 * @IntegrationTest
 * @Import(RedisTestContainerConfiguration.class)
 * class MyRedisBackedThingIntegrationTest { ... }
 * }</pre>
 *
 * Requires {@code spring-boot-starter-data-redis} (or equivalent) on the consuming service's own
 * classpath. Like any other {@code @ServiceConnection} target, this configuration only supplies
 * the container, not the client library.
 *
 * <p>A library module, not a service, that only wants this one piece (e.g.
 * {@code platform-common-messaging} testing {@code RedisEventIdempotencyGuard}) should still add
 * the whole {@code platform-common-test} module, since it's the only way to reach this class, but
 * exclude the test-slice starters it doesn't use: {@code spring-boot-starter-data-jpa-test},
 * {@code spring-boot-starter-webmvc-test} and {@code testcontainers-postgresql}. Left in, those
 * put {@code HibernateJpaAutoConfiguration}/{@code DataSourceAutoConfiguration} on every plain
 * {@code @SpringBootTest} in that module's classpath and break any test that never configured a
 * datasource. See {@code platform-common-messaging/pom.xml}'s {@code platform-common-test}
 * dependency for the exact exclusion block.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestContainerConfiguration {

    @Bean
    @ServiceConnection("redis")
    GenericContainer<?> redisContainer() {
        return RedisContainerHolder.CONTAINER;
    }
}
