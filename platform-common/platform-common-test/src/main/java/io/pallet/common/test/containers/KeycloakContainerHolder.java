package io.pallet.common.test.containers;

import java.time.Duration;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One lazily-started, never-explicitly-stopped Keycloak container per JVM fork, the same
 * singleton-container pattern as {@link PostgresContainerHolder}. Runs in dev mode with
 * {@code --import-realm} against {@code keycloak/pallet-test-realm.json} (bundled in this
 * module's own {@code src/main/resources}, so it ships regardless of which consuming service
 * uses it). The realm is {@code pallet-test}, with a public client {@code pallet-test-client}
 * that has direct-access-grants enabled (so a test can fetch a real token with a plain
 * username/password POST, no browser redirect flow) and two fixture users:
 * {@code test-owner} (roles {@code owner}, {@code admin}, {@code org_id=org-test-1}) and
 * {@code test-viewer} (role {@code viewer}, {@code org_id=org-test-2}). This mirrors the
 * {@code org_id} claim / realm-role shape that {@code docs/adr/0003-single-keycloak-realm.md}
 * and {@code infra/keycloak/pallet-realm.json} define for the real platform.
 *
 * <p>CONTRIBUTING.md requires Keycloak to be one of the dependencies integration tests hit for
 * real, never mock. This holder is what makes that possible without every service standing up
 * its own realm-import fixture.
 */
final class KeycloakContainerHolder {

    static final int HTTP_PORT = 8080;
    static final String REALM = "pallet-test";

    static final GenericContainer<?> CONTAINER = new GenericContainer<>(
                    ContainerImages.resolve("pallet.test.keycloak.image", "quay.io/keycloak/keycloak:26.4"))
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_HEALTH_ENABLED", "false")
            .withClasspathResourceMapping(
                    "keycloak/pallet-test-realm.json",
                    "/opt/keycloak/data/import/pallet-test-realm.json",
                    BindMode.READ_ONLY)
            .withExposedPorts(HTTP_PORT)
            .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)))
            .withReuse(true);

    static {
        CONTAINER.start();
    }

    private KeycloakContainerHolder() {}
}
