package za.co.fynbos.branchbook.shared.problem;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gives a validation constraint the code its {@code errors[]} entries carry.
 *
 * <p>Without it, the code is derived from the constraint's name: {@code @NotBlank} reports {@code
 * NOT_BLANK}. A custom constraint whose documented code differs from its name carries this
 * annotation instead, for example {@code @PersonName}, which reports {@code NAME_INVALID}
 * (docs/errors.md).
 */
@Documented
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ErrorCode {

  /**
   * Returns the code a violation of the annotated constraint reports.
   *
   * @return the code, in upper snake case
   */
  String value();
}
