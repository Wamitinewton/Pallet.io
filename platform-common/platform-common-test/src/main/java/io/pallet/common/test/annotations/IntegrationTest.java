package io.pallet.common.test.annotations;

import io.pallet.common.test.containers.KafkaTestContainerConfiguration;
import io.pallet.common.test.containers.PostgresTestContainerConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The full-context case: a real, singleton-shared Postgres and Kafka together, HTTP on a random
 * port. Postgres and Kafka are bundled together rather than offered as separate opt-in pieces.
 * The singleton-container pattern already makes an unused container in the same fork free, so
 * splitting further would only add a fifth or sixth annotation to maintain.
 *
 * <p>Boots real containers: classes using this annotation must be named {@code *IntegrationTest}
 * so Failsafe, not Surefire, runs them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, KafkaTestContainerConfiguration.class})
@Tag("integration")
public @interface IntegrationTest {}
