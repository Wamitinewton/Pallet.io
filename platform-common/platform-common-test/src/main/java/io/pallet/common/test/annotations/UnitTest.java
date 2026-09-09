package io.pallet.common.test.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A pure unit test: no Spring context, no container, {@code @Mock}/{@code @InjectMocks} fields
 * resolved by {@link MockitoExtension}. Runs under Surefire ({@code mvnw test}); the class must
 * be named {@code *Test}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(MockitoExtension.class)
@Tag("unit")
public @interface UnitTest {}
