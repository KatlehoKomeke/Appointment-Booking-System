package za.co.fynbos.branchbook.catalogue.api;

import java.time.format.DateTimeFormatter;
import java.util.List;
import org.jspecify.annotations.Nullable;
import za.co.fynbos.branchbook.catalogue.application.BranchQueries.BranchDetail;
import za.co.fynbos.branchbook.catalogue.domain.ClosedDay;
import za.co.fynbos.branchbook.catalogue.domain.OperatingHours;
import za.co.fynbos.branchbook.catalogue.domain.ServiceType;

/**
 * The body of {@code GET /api/v1/branches/{code}}.
 *
 * <p>Dates are ISO-8601 ({@code 2026-12-16}) and times are 24-hour {@code HH:mm} in the branch's
 * time zone. They are formatted here, explicitly, so the contract does not depend on JSON library
 * settings.
 *
 * @param code the branch code
 * @param name the branch's name
 * @param smsName the short name the confirmation SMS uses
 * @param address the street address
 * @param timezone the IANA time zone of the trading hours, for example {@code Africa/Johannesburg}
 * @param bookableFrom the first bookable date: today in the branch's time zone
 * @param bookableTo the last bookable date
 * @param operatingHours the weekly trading hours, Monday first; a missing day is a closed day
 * @param services the services the branch offers, in display order
 * @param closedDates the dates between {@code bookableFrom} and {@code bookableTo} on which the
 *     branch takes no appointments
 */
public record BranchDetailResponse(
    String code,
    String name,
    String smsName,
    Address address,
    String timezone,
    String bookableFrom,
    String bookableTo,
    List<Hours> operatingHours,
    List<Service> services,
    List<ClosedDate> closedDates) {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  static BranchDetailResponse from(BranchDetail detail) {
    var branch = detail.branch();
    var address = branch.address();
    return new BranchDetailResponse(
        branch.code(),
        branch.name(),
        branch.smsName(),
        new Address(
            address.line1(),
            address.suburb(),
            address.city(),
            address.province().displayName(),
            address.postalCode()),
        branch.timezone().getId(),
        detail.window().firstDate().toString(),
        detail.window().lastDate().toString(),
        detail.weeklyHours().stream().map(Hours::from).toList(),
        detail.services().stream().map(Service::from).toList(),
        detail.closedDays().stream().map(ClosedDate::from).toList());
  }

  /**
   * A branch's street address.
   *
   * @param line1 the street address
   * @param suburb the suburb
   * @param city the city or town
   * @param province the province
   * @param postalCode the four-digit postal code
   */
  public record Address(
      String line1, String suburb, String city, String province, String postalCode) {}

  /**
   * The trading hours of one day of the week.
   *
   * @param weekday the ISO day of the week: 1 is Monday, 7 is Sunday
   * @param opensAt the opening time, for example {@code 08:00}
   * @param closesAt the closing time, for example {@code 17:00}
   */
  public record Hours(int weekday, String opensAt, String closesAt) {

    static Hours from(OperatingHours hours) {
      return new Hours(
          hours.weekday().getValue(), TIME.format(hours.opensAt()), TIME.format(hours.closesAt()));
    }
  }

  /**
   * A service the branch offers.
   *
   * @param code the service code, for example {@code CARD}
   * @param name the service's name
   * @param description what the appointment is for
   * @param durationMinutes how long the appointment lasts
   * @param documentsNeeded what the customer must bring
   */
  public record Service(
      String code,
      String name,
      String description,
      int durationMinutes,
      List<String> documentsNeeded) {

    static Service from(ServiceType service) {
      return new Service(
          service.code(),
          service.name(),
          service.description(),
          service.durationMinutes(),
          service.documentsNeeded());
    }
  }

  /**
   * A date on which the branch takes no appointments.
   *
   * @param date the date
   * @param reason {@code CLOSED_DAY} or {@code PUBLIC_HOLIDAY}
   * @param name the holiday's name, or {@code null} for a closed day of the week
   */
  public record ClosedDate(String date, String reason, @Nullable String name) {

    static ClosedDate from(ClosedDay closed) {
      return new ClosedDate(closed.date().toString(), closed.reason().name(), closed.holidayName());
    }
  }
}
