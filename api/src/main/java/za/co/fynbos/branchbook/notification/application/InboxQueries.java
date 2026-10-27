package za.co.fynbos.branchbook.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.booking.application.AppointmentDirectory;
import za.co.fynbos.branchbook.booking.application.AppointmentLabel;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;

/**
 * Reads the simulated inbox: the newest messages of every appointment, each with its reference and
 * its recipient redacted (FR-17). The appointments are named through booking's {@link
 * AppointmentDirectory}, so this module still reads no table of another. A cancellation whose
 * appointment the cancel has deleted has none to name, so it shows {@code APT-****-****}. Whether
 * each message is {@code retryable} is decided by {@link RetryPolicy}, the rule the retry itself
 * applies.
 */
@Service
public class InboxQueries {

  private final NotificationMessageRepository messages;
  private final AppointmentDirectory appointments;
  private final Clock clock;

  InboxQueries(
      NotificationMessageRepository messages, AppointmentDirectory appointments, Clock clock) {
    this.messages = messages;
    this.appointments = appointments;
    this.clock = clock;
  }

  /**
   * Lists the newest messages.
   *
   * <p>The messages and their appointments are read in two statements, so both run in one
   * REPEATABLE READ snapshot. Under READ COMMITTED each statement sees the latest commit, and a
   * retention purge committing between the two would leave a message whose appointment is gone. A
   * read-only REPEATABLE READ transaction never fails to serialize, so it costs nothing.
   *
   * @param limit the most messages to return, already checked to be from 1 to 200
   * @return the messages, newest first
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<InboxMessage> newest(int limit) {
    Instant now = clock.instant();
    List<NotificationMessage> newest = messages.newest(limit);
    Map<UUID, AppointmentLabel> labels =
        appointments.labels(
            newest.stream()
                .map(NotificationMessage::appointmentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
    return newest.stream()
        .map(message -> InboxMessage.of(message, labelOf(message, labels), now))
        .toList();
  }

  /** The label of a message's appointment, or {@code null} for a message that has none left. */
  private static @Nullable AppointmentLabel labelOf(
      NotificationMessage message, Map<UUID, AppointmentLabel> labels) {
    UUID appointmentId = message.appointmentId();
    return appointmentId == null ? null : labels.get(appointmentId);
  }
}
