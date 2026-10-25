package za.co.fynbos.branchbook.booking.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/** Checks {@link IsoOffsetDateTime} with the same formatter the request is parsed with. */
class IsoOffsetDateTimeValidator implements ConstraintValidator<IsoOffsetDateTime, String> {

  @Override
  public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    try {
      OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      return true;
    } catch (DateTimeParseException notAnInstant) {
      return false;
    }
  }
}
