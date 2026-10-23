package za.co.fynbos.branchbook.catalogue.domain;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A date on which a branch takes no appointments, and why.
 *
 * @param date the date
 * @param reason why the branch is closed
 * @param holidayName the holiday's name when the reason is {@link Reason#PUBLIC_HOLIDAY}, otherwise
 *     {@code null}
 */
public record ClosedDay(LocalDate date, Reason reason, @Nullable String holidayName) {

  /** Rejects a holiday without a name, and a name on a day that is not a holiday. */
  public ClosedDay {
    Objects.requireNonNull(date, "date");
    Objects.requireNonNull(reason, "reason");
    if ((reason == Reason.PUBLIC_HOLIDAY) != (holidayName != null)) {
      throw new IllegalArgumentException("Only a public holiday has a holiday name");
    }
  }

  /** Why a branch is closed on a date. The API returns these names as {@code reason}. */
  public enum Reason {
    /** The branch does not trade on that day of the week, for example a Sunday. */
    CLOSED_DAY,
    /** It is a public holiday, including a Monday observed in place of a Sunday holiday. */
    PUBLIC_HOLIDAY
  }
}
