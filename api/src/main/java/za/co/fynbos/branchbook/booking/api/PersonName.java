package za.co.fynbos.branchbook.booking.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import za.co.fynbos.branchbook.shared.problem.ErrorCode;

/**
 * A field that holds a person's name, by the rules of {@code CustomerName}: letters, combining
 * marks, spaces, hyphens and apostrophes, up to 60 characters. A blank value is left to {@code
 * NotBlank}. A violation reports {@code NAME_INVALID}.
 */
@Documented
@Constraint(validatedBy = PersonNameValidator.class)
@ErrorCode("NAME_INVALID")
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PersonName {

  /**
   * Returns the message of a violation.
   *
   * @return the message, which never quotes the value
   */
  String message() default "use letters, spaces, hyphens or apostrophes, up to 60 characters";

  /**
   * Returns the validation groups.
   *
   * @return the groups, none by default
   */
  Class<?>[] groups() default {};

  /**
   * Returns the payload.
   *
   * @return the payload, none by default
   */
  Class<? extends Payload>[] payload() default {};
}
