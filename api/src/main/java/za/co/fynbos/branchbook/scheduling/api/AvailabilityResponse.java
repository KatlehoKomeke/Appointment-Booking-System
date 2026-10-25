package za.co.fynbos.branchbook.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(name = "Availability")
public record AvailabilityResponse(
    @Schema(example = "SAN") String branchCode,
    @Schema(example = "FICA") String serviceCode,
    @Schema(format = "date", example = "2026-10-27") String date,
    @Schema(
            example = "Africa/Johannesburg",
            description = "The branch's IANA time zone, whose offset every instant carries")
        String timezone,
    @Schema(
            format = "date-time",
            example = "2026-10-26T10:05:12+02:00",
            description = "When the answer was computed, so a page can tell how fresh it is")
        String generatedAt,
    @Schema(
            types = {"string", "null"},
            allowableValues = {"CLOSED_DAY", "PUBLIC_HOLIDAY"},
            description = "Why the branch is closed that day; null if it trades")
        @Nullable String closedReason,
    @Schema(
            types = {"string", "null"},
            example = "Day of Reconciliation",
            description = "The holiday's name when closedReason is PUBLIC_HOLIDAY; otherwise null")
        @Nullable String holidayName,
    @Schema(
            description =
                "The day's slots in time order. Empty on a closed day, and once every slot left"
                    + " today starts within the lead time.")
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
  @Schema(name = "Slot")
  public record SlotResponse(
      @Schema(format = "date-time", example = "2026-10-27T09:30:00+02:00") String startsAt,
      @Schema(format = "date-time", example = "2026-10-27T09:45:00+02:00") String endsAt,
      @Schema(
              minimum = "0",
              example = "2",
              description = "The seats still free; 0 means full, and the slot is still listed")
          int seatsAvailable) {

    static SlotResponse from(Slot slot, ZoneId zone) {
      return new SlotResponse(
          TimeFormat.isoOffset(slot.startsAt(), zone),
          TimeFormat.isoOffset(slot.endsAt(), zone),
          slot.seatsAvailable());
    }
  }
}
