package io.pallet.orgteam.config;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;

@Documented
@Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NonNegativeDuration.Validator.class)
public @interface NonNegativeDuration {

    String message() default "must not be a negative duration";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NonNegativeDuration, Duration> {

        @Override
        public boolean isValid(Duration value, ConstraintValidatorContext context) {
            return value == null || !value.isNegative();
        }
    }
}
