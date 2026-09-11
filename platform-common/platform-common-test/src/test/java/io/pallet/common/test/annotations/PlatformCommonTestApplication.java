package io.pallet.common.test.annotations;

import io.pallet.common.api.PalletApiAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal bootable application this module's own {@code @RepositoryTest}, {@code @ControllerTest}
 * and {@code @IntegrationTest} annotation tests bootstrap against. {@code platform-common-test} is
 * a library, not a service, so it has no real {@code @SpringBootApplication}. Spring's test
 * context bootstrapper finds this class by walking up from a test class's package looking for a
 * {@code @SpringBootConfiguration}, the same mechanism a real service's main class satisfies for
 * its own tests. Entity/repository/{@code @Controller} scanning is rooted at this class's package
 * (via the {@code @ComponentScan} {@code @SpringBootApplication} carries), which is why
 * {@code fixtures} lives directly underneath it: a bare {@code @SpringBootConfiguration
 * @EnableAutoConfiguration} would leave test slices with nothing to scan.
 *
 * <p>{@link PalletApiAutoConfiguration} is excluded so the fixture controllers' literal paths
 * ({@code /test-entities/missing}, {@code /secured/whoami}) stay reachable unprefixed — this
 * fixture app probes other modules' auto-configurations, not the API path prefix itself.
 */
@SpringBootApplication(exclude = PalletApiAutoConfiguration.class)
class PlatformCommonTestApplication {}
