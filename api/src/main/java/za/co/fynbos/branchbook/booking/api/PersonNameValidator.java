package za.co.fynbos.branchbook.booking.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;
import za.co.fynbos.branchbook.booking.domain.CustomerName;

/** Checks {@link PersonName} with the parser the booking uses, so the two can never disagree. */
class PersonNameValidator implements ConstraintValidator<PersonName, String> {

  @Override
  public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
    return value == null || value.isBlank() || CustomerName.parse(value).isPresent();
  }
}
