package io.pallet.common.test.annotations;

import io.pallet.common.test.containers.PostgresTestContainerConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * A JPA repository slice against a real, singleton-shared Postgres container.
 * {@code replace = NONE} is required: {@code @DataJpaTest} defaults to swapping the configured
 * datasource for an embedded H2, which doesn't speak {@code jsonb}. Every {@code @RepositoryTest}
 * must hit the real database, or it silently stops testing the thing that actually differs
 * between databases.
 *
 * <p>Each test method runs in a transaction rolled back at the end (a {@code @DataJpaTest}
 * default). A repository test migrated from a hand-rolled setup that relied on committed state
 * carrying across methods in the same class should drop that manual cleanup, not keep it.
 *
 * <p>Boots a real container: classes using this annotation must be named {@code *IntegrationTest}
 * so Failsafe, not Surefire, runs them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Tag("repository")
public @interface RepositoryTest {}
