package io.pallet.common.test.containers;

import org.testcontainers.utility.DockerImageName;

/**
 * Resolves the Docker image a container holder starts, defaulting to the platform-pinned version
 * but letting a consuming build override it with a system property. This supports a service that
 * needs a different major version for compatibility testing, or a build profile that pins images
 * to an internal mirror. Set the property on the JVM running the tests, e.g.
 * {@code -Dpallet.test.postgres.image=postgres:16} or a {@code <systemPropertyVariables>} entry
 * on the Surefire/Failsafe plugin in a service's own {@code pom.xml}.
 *
 * <p>Each holder's {@code static final CONTAINER} field is initialized at class-load time, so
 * the property must be set before the holder class is first touched: a JVM or plugin-level
 * system property, not one set from within a test method.
 */
final class ContainerImages {

    private ContainerImages() {}

    static DockerImageName resolve(String systemProperty, String defaultImage) {
        return DockerImageName.parse(System.getProperty(systemProperty, defaultImage));
    }
}
