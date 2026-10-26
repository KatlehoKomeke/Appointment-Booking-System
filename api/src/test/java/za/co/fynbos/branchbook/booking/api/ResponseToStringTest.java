package za.co.fynbos.branchbook.booking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import za.co.fynbos.branchbook.booking.application.NotificationSummary;

/**
 * BR-19: a response, or the message summary behind it, passed to a logger by mistake shows no
 * reference, name, number or message text. The values are synthetic.
 */
class ResponseToStringTest {

  private static final String REFERENCE = "APT-7K3M-9Q2X";
  private static final String SMS =
      "Appt confirmed: FICA update, Sandton Summit, Tue 27 Oct 09:30 SAST. Ref "
          + REFERENCE
          + ". Bring ID + docs. Manage: http://localhost:8080/manage";
  private static final UUID MESSAGE = UUID.fromString("dccc2431-28aa-4926-a555-ca6c80ddfd88");

  @Test
  void appointmentResponseShowsNeitherTheReferenceNorTheCustomerNorTheMessages() {
    NotificationResponse sms =
        new NotificationResponse(
            MESSAGE, "SMS", "CONFIRMATION", "PENDING", 0, null, null, "082 *** 0101", SMS);
    AppointmentResponse response =
        new AppointmentResponse(
            REFERENCE,
            "CONFIRMED",
            new AppointmentResponse.BranchResponse("SAN", "Sandton Summit Mall", "Shop 4"),
            new AppointmentResponse.ServiceResponse(
                "FICA", "Update personal details (FICA)", 15, List.of()),
            "2026-10-27T09:30:00+02:00",
            "2026-10-27T09:45:00+02:00",
            new AppointmentResponse.CustomerResponse("Nomvula", "Dlamini", "082 *** 0101"),
            true,
            null,
            List.of(sms));

    assertThat(response).hasToString("AppointmentResponse[CONFIRMED, redacted]");
    assertThat(sms).hasToString("NotificationResponse[CONFIRMATION, PENDING, redacted]");
  }

  @Test
  void notificationSummaryShowsNeitherTheRecipientNorTheBody() {
    NotificationSummary summary =
        new NotificationSummary(
            MESSAGE,
            "SMS",
            "CONFIRMATION",
            "SENT",
            1,
            Instant.parse("2026-10-26T06:00:02Z"),
            null,
            "082 *** 0101",
            SMS);

    assertThat(summary).hasToString("NotificationSummary[CONFIRMATION, SENT, redacted]");
  }
}
