package io.pallet.common.test.containers;

import org.testcontainers.containers.GenericContainer;

/**
 * One lazily-started, never-explicitly-stopped Redis container per JVM fork, the same
 * singleton-container pattern as {@link PostgresContainerHolder}. A plain {@link GenericContainer}
 * is enough: Spring Boot's {@code RedisContainerConnectionDetailsFactory} recognizes any
 * {@code @ServiceConnection}-annotated container whose image is {@code redis} and binds
 * {@code spring.data.redis.*} from it, so no dedicated Testcontainers Redis module is needed.
 */
final class RedisContainerHolder {

    static final GenericContainer<?> CONTAINER = new GenericContainer<>(
                    ContainerImages.resolve("pallet.test.redis.image", "redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    static {
        CONTAINER.start();
    }

    private RedisContainerHolder() {}
}
