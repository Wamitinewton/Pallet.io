package io.pallet.common.test.containers;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One lazily-started, never-explicitly-stopped Postgres container per JVM fork. Every
 * {@code @RepositoryTest} and {@code @IntegrationTest} class in the same Surefire/Failsafe fork
 * gets this same running container via {@link PostgresTestContainerConfiguration}, rather than
 * paying startup cost per class. Ryuk reaps it when the JVM exits; nothing here ever calls
 * {@code .stop()}.
 *
 * <p>Image defaults to {@code postgres:17}; override with {@code -Dpallet.test.postgres.image=...}
 * (see {@link ContainerImages}) for a service that needs a different version or an extension.
 */
final class PostgresContainerHolder {

    static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(
                    ContainerImages.resolve("pallet.test.postgres.image", "postgres:17"))
            .withReuse(true);

    static {
        CONTAINER.start();
    }

    private PostgresContainerHolder() {}
}
