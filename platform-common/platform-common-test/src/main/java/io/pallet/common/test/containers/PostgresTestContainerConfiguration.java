package io.pallet.common.test.containers;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Hands {@link PostgresContainerHolder}'s already-running singleton to Spring via
 * {@code @ServiceConnection}, which reads connection details off the bean and binds
 * {@code spring.datasource.*} itself, with no {@code @DynamicPropertySource} needed. Returning
 * the holder's static instance, rather than a per-class {@code @Container} field, is what keeps
 * the container a singleton: this configuration only ever reads from it, never starts or stops
 * it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainerConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return PostgresContainerHolder.CONTAINER;
    }
}
