package io.pallet.common.test.annotations;

import io.pallet.common.test.containers.KafkaTestContainerConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * A full application context with a real, singleton-shared Kafka broker but no Postgres. Use
 * this when a test only needs to prove a {@code @KafkaListener} consumes and dedupes correctly,
 * without paying for the rest of the app's Postgres-backed persistence layer.
 *
 * <p>The {@code @EnableAutoConfiguration(exclude = ...)} is required, not optional. Almost every
 * real Pallet service has {@code spring-boot-starter-data-jpa} on its main classpath (per
 * ADR-0008, even the idempotency guard is commonly Postgres-unique-constraint based), so without
 * these exclusions {@code @EnableAutoConfiguration} would still try to wire a
 * {@code DataSource}/{@code EntityManagerFactory} against {@code spring.datasource.*}, which
 * nothing here ever sets, and the context would fail to start even though the test under it
 * never touches a repository. Excluding them explicitly is what actually removes Postgres from
 * the context, rather than merely relying on the service under test having none either.
 * {@code MessagingIntegrationTestAnnotationTest} proves this: it boots against a fixture app
 * that <em>does</em> carry a JPA entity/repository and confirms no {@code DataSource} bean
 * exists in the resulting context.
 *
 * <p>Boots a real container: classes using this annotation must be named {@code *IntegrationTest}
 * so Failsafe, not Surefire, runs them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@EnableAutoConfiguration(
        exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class
        })
@ActiveProfiles("test")
@Import(KafkaTestContainerConfiguration.class)
@Tag("messaging")
public @interface MessagingIntegrationTest {}
