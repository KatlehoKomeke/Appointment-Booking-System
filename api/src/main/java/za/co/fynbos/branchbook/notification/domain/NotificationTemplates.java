package za.co.fynbos.branchbook.notification.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * The text of the confirmation and the cancellation SMS (BR-18), for example:
 *
 * <pre>
 * Appt confirmed: FICA update, Sandton Summit, Tue 27 Oct 09:30 SAST. Ref APT-7K3M-9Q2X. Bring ID +
 * docs. Manage: http://localhost:8080/manage
 *
 * Appt cancelled: FICA update, Sandton Summit, Tue 27 Oct 09:30 SAST. Ref APT-7K3M-9Q2X. Rebook:
 * http://localhost:8080/book
 * </pre>
 *
 * <p>Each states the service, the branch, the time in South African Standard Time, the reference
 * and a link, and nothing else: never the mobile number, its last four digits or the customer's
 * name. Every branch is in {@code Africa/Johannesburg}, so the time is labelled SAST. Day and month
 * names are always English, whatever the server's default locale.
 *
 * <p>Each always fits one GSM-7 segment: the fixed text, the longest short names the database
 * allows ({@value #SERVICE_SMS_NAME_MAX} and {@value #BRANCH_SMS_NAME_MAX} characters, letters,
 * digits, spaces, hyphens and apostrophes only) and the configured base URL add up to at most 160
 * septets, which {@link #longestConfirmationSeptets} and {@link #longestCancellationSeptets}
 * compute from the templates themselves and the application checks at start-up. Rendering never
 * fails on length, so a template change can never make a booking or a cancellation fail.
 */
public final class NotificationTemplates {

  /** The longest service short name: the size of {@code services.sms_name}. */
  public static final int SERVICE_SMS_NAME_MAX = 18;

  /** The longest branch short name: the size of {@code branches.sms_name}. */
  public static final int BRANCH_SMS_NAME_MAX = 20;

  private static final String CONFIRMATION =
      "Appt confirmed: %s, %s, %s SAST. Ref %s. Bring ID + docs. Manage: %s/manage";

  private static final String CANCELLATION =
      "Appt cancelled: %s, %s, %s SAST. Ref %s. Rebook: %s/book";

  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);

  /** A start with a two-digit day: the longest the time can render. */
  private static final Instant TWO_DIGIT_DAY = Instant.parse("2026-10-27T09:30:00Z");

  private final String publicBaseUrl;

  /**
   * Creates the templates for the address customers reach the app at.
   *
   * @param publicBaseUrl the app's public address without a trailing slash, for example {@code
   *     http://localhost:8080}
   */
  public NotificationTemplates(String publicBaseUrl) {
    this.publicBaseUrl = Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
  }

  /**
   * Renders the confirmation SMS.
   *
   * @param serviceSmsName the service's short name, for example {@code FICA update}
   * @param branchSmsName the branch's short name, for example {@code Sandton Summit}
   * @param startsAt when the appointment starts
   * @param zone the branch's time zone
   * @param reference the appointment's reference
   * @return the text of the SMS
   */
  public String confirmationSms(
      String serviceSmsName,
      String branchSmsName,
      Instant startsAt,
      ZoneId zone,
      String reference) {
    return render(CONFIRMATION, serviceSmsName, branchSmsName, startsAt, zone, reference);
  }

  /**
   * Renders the cancellation SMS. It names the same service, branch and time as the confirmation,
   * so the customer can tell which appointment it means, and links to booking again.
   *
   * @param serviceSmsName the service's short name, for example {@code FICA update}
   * @param branchSmsName the branch's short name, for example {@code Sandton Summit}
   * @param startsAt when the appointment would have started
   * @param zone the branch's time zone
   * @param reference the appointment's reference
   * @return the text of the SMS
   */
  public String cancellationSms(
      String serviceSmsName,
      String branchSmsName,
      Instant startsAt,
      ZoneId zone,
      String reference) {
    return render(CANCELLATION, serviceSmsName, branchSmsName, startsAt, zone, reference);
  }

  /**
   * Computes the septets of the longest confirmation this template can produce with a base URL: the
   * longest short names, a two-digit day and a full reference.
   *
   * @param publicBaseUrl the base URL to check
   * @return the septets of the worst case
   * @throws IllegalArgumentException if the base URL has a character outside the GSM-7 alphabet
   */
  public static int longestConfirmationSeptets(String publicBaseUrl) {
    return Gsm7.septets(worstCase(CONFIRMATION, publicBaseUrl));
  }

  /**
   * Computes the septets of the longest cancellation this template can produce with a base URL, in
   * the same way as {@link #longestConfirmationSeptets}.
   *
   * @param publicBaseUrl the base URL to check
   * @return the septets of the worst case
   * @throws IllegalArgumentException if the base URL has a character outside the GSM-7 alphabet
   */
  public static int longestCancellationSeptets(String publicBaseUrl) {
    return Gsm7.septets(worstCase(CANCELLATION, publicBaseUrl));
  }

  /** A template rendered with the longest short names, a two-digit day and a full reference. */
  private static String worstCase(String template, String publicBaseUrl) {
    return new NotificationTemplates(publicBaseUrl)
        .render(
            template,
            "X".repeat(SERVICE_SMS_NAME_MAX),
            "X".repeat(BRANCH_SMS_NAME_MAX),
            TWO_DIGIT_DAY,
            ZoneOffset.UTC,
            "APT-XXXX-XXXX");
  }

  private String render(
      String template,
      String serviceSmsName,
      String branchSmsName,
      Instant startsAt,
      ZoneId zone,
      String reference) {
    return template.formatted(
        serviceSmsName,
        branchSmsName,
        WHEN.format(startsAt.atZone(zone)),
        reference,
        publicBaseUrl);
  }
}
