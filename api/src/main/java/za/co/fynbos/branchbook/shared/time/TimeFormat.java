package za.co.fynbos.branchbook.shared.time;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The one way an instant leaves the API: ISO-8601 with the offset of a branch's time zone, for
 * example {@code 2026-10-27T09:30:00+02:00} (BR-20).
 *
 * <p>Response records carry these as strings, formatted here explicitly, so the offset never
 * depends on the JSON library's time-zone settings or the JVM's default zone. A client reads the
 * branch's wall-clock time straight from the string, and the instant is still unambiguous.
 */
public final class TimeFormat {

  private TimeFormat() {}

  /**
   * Formats an instant at a time zone's offset.
   *
   * @param instant the instant to format
   * @param zone the zone whose offset to show, normally the branch's
   * @return for example {@code 2026-10-27T09:30:00+02:00}
   */
  public static String isoOffset(Instant instant, ZoneId zone) {
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(zone).toOffsetDateTime());
  }
}
