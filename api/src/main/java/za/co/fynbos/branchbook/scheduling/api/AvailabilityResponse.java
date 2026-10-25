package za.co.fynbos.branchbook.scheduling.api;

import java.time.ZoneId;
import java.util.List;
import org.jspecify.annotations.Nullable;
import za.co.fynbos.branchbook.catalogue.domain.ClosedDay;
import za.co.fynbos.branchbook.scheduling.application.AvailabilityQueries.DayAvailability;
import za.co.fynbos.branchbook.scheduling.domain.Slot;
import za.co.fynbos.branchbook.shared.time.TimeFormat;

/**
 * The body of {@code GET /api/v1/branches/{code}/availability}.
 *
 * <p>Instants carry the branch's offset, for example {@code 2026-10-27T09:30:00+02:00}, so the
 * branch's wall-clock time can be read straight from the string (BR-20).
 *
 * @param branchCode the branch code
 * @param serviceCode the service code
 * @param date the date, in the branch's time zone
 * @param timezone the branch's IANA time zone
 * @param generatedAt when the answer was computed
 * @param closedReason why the branch is closed that day, or {@code null} if it trades
 * @param holidayName the public holiday's name when the reason is {@code PUBLIC_HOLIDAY}
 * @param slots the day's slots in time order; empty on a closed day
 */
public record AvailabilityResponse(
    String branchCode,
    String serviceCode,
    String date,
    String timezone,
    String generatedAt,
    @Nullable String closedReason,
    @Nullable String holidayName,
    List<SlotResponse> slots) {

  static AvailabilityResponse from(DayAvailability day) {
    ZoneId zone = day.branch().timezone();
    ClosedDay closure = day.closure();
    return new AvailabilityResponse(
        day.branch().code(),
        day.service().code(),
        day.date().toString(),
        zone.getId(),
        TimeFormat.isoOffset(day.generatedAt(), zone),
        closure == null ? null : closure.reason().name(),
        closure == null ? null : closure.holidayName(),
        day.slots().stream().map(slot -> SlotResponse.from(slot, zone)).toList());
  }

  /**
   * One slot.
   *
   * @param startsAt when the appointment would start
   * @param endsAt when it would end
   * @param seatsAvailable the seats still free; 0 means the slot is full
   */
  public record SlotResponse(String startsAt, String endsAt, int seatsAvailable) {

    static SlotResponse from(Slot slot, ZoneId zone) {
      return new SlotResponse(
          TimeFormat.isoOffset(slot.startsAt(), zone),
          TimeFormat.isoOffset(slot.endsAt(), zone),
          slot.seatsAvailable());
    }
  }
}
