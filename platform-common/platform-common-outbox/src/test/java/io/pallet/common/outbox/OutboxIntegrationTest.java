package io.pallet.common.outbox;

import io.pallet.common.test.containers.KafkaTestContainerConfiguration;
import io.pallet.common.test.containers.PostgresTestContainerConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code @IntegrationTest} without the web environment: the module serves no HTTP, so {@link OutboxTestApplication}
 * boots against the same singleton Postgres and Kafka containers with no server.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(classes = OutboxTestApplication.class)
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, KafkaTestContainerConfiguration.class})
@Tag("integration")
public @interface OutboxIntegrationTest {}
