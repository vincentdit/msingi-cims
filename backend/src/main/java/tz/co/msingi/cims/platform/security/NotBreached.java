package tz.co.msingi.cims.platform.security;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The password is not one of the commonly used or guessed ones (NFR-SEC-01). */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotBreached.Validator.class)
public @interface NotBreached {
    String message() default "This password is too common and easy to guess. Choose another.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NotBreached, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || !BreachedPasswords.isBreached(value);
        }
    }
}
