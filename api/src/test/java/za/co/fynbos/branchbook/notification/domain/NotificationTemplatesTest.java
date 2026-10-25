package za.co.fynbos.branchbook.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** BR-18: the confirmation SMS says what it must, in English, in one GSM-7 segment. */
class NotificationTemplatesTest {

  private static final ZoneId JOHANNESBURG = ZoneId.of("Africa/Johannesburg");

  /** The short names V3__reference_data.sql seeds. */
  private static final List<String> SEEDED_BRANCHES =
      List.of(
          "Sandton Summit",
          "Bellville Kingsway",
          "Umhlanga Ridge",
          "Gqeberha Baywater",
          "Polokwane Savannah",
          "Rustenburg Bojanala");

  private static final List<String> SEEDED_SERVICES =
      List.of(
          "FICA update",
          "Card replacement",
          "Account opening",
          "Credit consult",
          "Deceased estate",
          "Home loan consult");

  /** The base URL docker compose sets locally. */
  private final NotificationTemplates templates =
      new NotificationTemplates("http://localhost:8080");

  @Test
  void rendersThePublishedExampleExactly() {
    String sms =
        templates.confirmationSms(
            "FICA update",
            "Sandton Summit",
            Instant.parse("2026-10-27T07:30:00Z"),
            JOHANNESBURG,
            "APT-7K3M-9Q2X");

    assertThat(sms)
        .isEqualTo(
            "Appt confirmed: FICA update, Sandton Summit, Tue 27 Oct 09:30 SAST. Ref APT-7K3M-9Q2X."
                + " Bring ID + docs. Manage: http://localhost:8080/manage");
  }

  @Test
  void confirmationFitsOneGsm7SegmentForLongestSeededNames() {
    Instant twoDigitDay = Instant.parse("2026-11-28T09:30:00Z");
    for (String branch : SEEDED_BRANCHES) {
      for (String service : SEEDED_SERVICES) {
        String sms =
            templates.confirmationSms(service, branch, twoDigitDay, JOHANNESBURG, "APT-7K3M-9Q2X");

        assertThat(Gsm7.isEncodable(sms)).as(sms).isTrue();
        assertThat(Gsm7.septets(sms)).as(sms).isLessThanOrEqualTo(Gsm7.SINGLE_SEGMENT);
      }
    }
  }

  /**
   * The worst case the database allows, with the longest base URL start-up accepts, is exactly one
   * segment: 28 characters of base URL are the whole margin.
   */
  @Test
  void longestCombinationWithTheLongestAllowedBaseUrlFitsOneSegment() {
    String longestBaseUrl = "https://" + "b".repeat(20);

    assertThat(longestBaseUrl).hasSize(28);
    assertThat(NotificationTemplates.longestConfirmationSeptets(longestBaseUrl))
        .isEqualTo(Gsm7.SINGLE_SEGMENT);
  }

  /** A server with a German default locale must still write Wed 30 Sep, never Mi. 30 Sept. */
  @Test
  void rendersEnglishDayNamesUnderAnyDefaultLocale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.GERMANY);
      String sms =
          templates.confirmationSms(
              "FICA update",
              "Sandton Summit",
              Instant.parse("2026-09-30T07:30:00Z"),
              JOHANNESBURG,
              "APT-7K3M-9Q2X");

      assertThat(sms).contains("Wed 30 Sep 09:30 SAST").doesNotContain("Mi").doesNotContain("Sept");
    } finally {
      Locale.setDefault(original);
    }
  }
}
