package za.co.fynbos.branchbook.catalogue.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A public holiday as the law states it (table {@code public_holidays}).
 *
 * @param date the date of the holiday
 * @param name its official name, for example {@code Day of Reconciliation}
 * @param source the law that makes it a holiday
 */
public record PublicHoliday(LocalDate date, String name, Source source) {

  /** Rejects a missing value. */
  public PublicHoliday {
    Objects.requireNonNull(date, "date");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(source, "source");
  }

  /** The law that makes a day a public holiday. */
  public enum Source {
    /** Schedule 1 of the Public Holidays Act 36 of 1994. */
    SCHEDULE_1,
    /** A day the President declares under section 2A of the Act, such as an election day. */
    PROCLAMATION
  }
}
