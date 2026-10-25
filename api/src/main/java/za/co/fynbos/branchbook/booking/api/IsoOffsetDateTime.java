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
 * A field that holds an ISO-8601 date and time with an explicit offset, such as {@code
 * 2026-10-27T09:30:00+02:00}. A time without an offset is ambiguous, so it is refused rather than
 * read in some zone (BR-20). A missing value is left to {@code NotNull}. A violation reports {@code
 * OFFSET_REQUIRED}.
 */
@Documented
@Constraint(validatedBy = IsoOffsetDateTimeValidator.class)
@ErrorCode("OFFSET_REQUIRED")
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IsoOffsetDateTime {

  /**
   * Returns the message of a violation.
   *
   * @return the message, which never quotes the value
   */
  String message() default "must be an ISO-8601 date and time with an offset";

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
