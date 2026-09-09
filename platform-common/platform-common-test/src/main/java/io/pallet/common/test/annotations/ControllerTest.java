package io.pallet.common.test.annotations;

import io.pallet.common.error.PalletErrorHandlingAutoConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.ActiveProfiles;

/**
 * A controller slice. No container is started, since a controller test needs neither Postgres
 * nor Kafka.
 *
 * <p>{@code @WebMvcTest} carries {@code @OverrideAutoConfiguration(enabled = false)}: it loads
 * only Boot's own curated MVC autoconfiguration, not the full {@code AutoConfiguration.imports}
 * set a real application would evaluate. That means {@code platform-common-exception}'s
 * {@code PalletErrorHandlingAutoConfiguration} does <strong>not</strong> activate on its own
 * under this slice. This annotation {@code @Import}s it explicitly so every
 * {@code @ControllerTest} still gets the real {@code AppException -> ErrorResponse} rendering,
 * the same JSON a production error response would return, without every consuming service having
 * to remember to wire it in. {@code ControllerTestAnnotationTest} is the regression test for this
 * import: drop it, and the exception advice silently stops firing.
 *
 * <p>Servlet filters, including any {@code SecurityFilterChain}, are disabled by default
 * ({@code @AutoConfigureMockMvc(addFilters = false)}), the same way {@code @WebMvcTest} behaves
 * with no security starter on the classpath at all. Without this, a service that adds
 * {@code platform-common-test}'s optional JWT-mocking helper (because <em>some</em> of its
 * controllers are secured) would find every <em>other</em>, unsecured {@code @ControllerTest} in
 * the same service suddenly returning 401. Spring Security's default-deny autoconfiguration
 * activates from the mere presence of the security starter on the classpath, regardless of which
 * controller a given slice test targets. A test that specifically wants to prove a controller's
 * authorization rules re-enables filters with {@code @AutoConfigureMockMvc(addFilters = true)}
 * alongside {@code io.pallet.common.test.security.PalletJwtRequestPostProcessors}. See
 * {@code PalletJwtRequestPostProcessorsControllerTest}.
 *
 * <p>Touches no external process: classes using this annotation must be named {@code *Test} so
 * Surefire, not Failsafe, runs them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(PalletErrorHandlingAutoConfiguration.class)
@Tag("controller")
public @interface ControllerTest {

    @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
    Class<?>[] value() default {};
}
