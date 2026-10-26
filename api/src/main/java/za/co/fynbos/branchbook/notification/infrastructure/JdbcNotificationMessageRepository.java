package za.co.fynbos.branchbook.notification.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import za.co.fynbos.branchbook.notification.application.NotificationMessageRepository;
import za.co.fynbos.branchbook.notification.domain.NotificationChannel;
import za.co.fynbos.branchbook.notification.domain.NotificationKind;
import za.co.fynbos.branchbook.notification.domain.NotificationMessage;
import za.co.fynbos.branchbook.notification.domain.NotificationStatus;

/**
 * Stores messages in {@code notification_messages} with plain SQL. Every instant is bound from the
 * caller, never taken from the database's {@code now()}, so tests control time (BR-20).
 */
@Repository
class JdbcNotificationMessageRepository implements NotificationMessageRepository {

  private static final String INSERT =
      """
      INSERT INTO notification_messages
          (id, appointment_id, kind, channel, recipient, subject, body, status, attempts,
           next_attempt_at, failure_reason, sent_at, correlation_id, created_at)
      VALUES (:id, :appointmentId, :kind, :channel, :recipient, :subject, :body, :status, :attempts,
              :nextAttemptAt, :failureReason, :sentAt, :correlationId, :createdAt)""";

  private static final String COLUMNS =
      """
      id, appointment_id, kind, channel, recipient, subject, body, status, attempts, next_attempt_at,
      last_error, failure_reason, sent_at, correlation_id, created_at""";

  private static final String FOR_APPOINTMENT =
      "SELECT "
          + COLUMNS
          + " FROM notification_messages WHERE appointment_id = :appointmentId"
          + " ORDER BY created_at, id";

  private static final String CLAIM_NEXT_DUE =
      "SELECT "
          + COLUMNS
          + " FROM notification_messages"
          + " WHERE status = 'PENDING' AND next_attempt_at <= :now"
          + " ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED";

  private static final String NEWEST =
      "SELECT "
          + COLUMNS
          + " FROM notification_messages ORDER BY created_at DESC, id DESC"
          + " LIMIT :limit";

  private final JdbcClient jdbc;

  JdbcNotificationMessageRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(NotificationMessage message) {
    jdbc.sql(INSERT)
        .param("id", message.id())
        .param("appointmentId", message.appointmentId())
        .param("kind", message.kind().name())
        .param("channel", message.channel().name())
        .param("recipient", message.recipient())
        .param("subject", message.subject())
        .param("body", message.body())
        .param("status", message.status().name())
        .param("attempts", message.attempts())
        .param("nextAttemptAt", utc(message.nextAttemptAt()))
        .param("failureReason", message.failureReason())
        .param("sentAt", utc(message.sentAt()))
        .param("correlationId", message.correlationId())
        .param("createdAt", utc(message.createdAt()))
        .update();
  }

  @Override
  public List<NotificationMessage> forAppointment(UUID appointmentId) {
    return jdbc.sql(FOR_APPOINTMENT)
        .param("appointmentId", appointmentId)
        .query((rs, row) -> message(rs))
        .list();
  }

  @Override
  public List<NotificationMessage> newest(int limit) {
    return jdbc.sql(NEWEST).param("limit", limit).query((rs, row) -> message(rs)).list();
  }

  @Override
  public Optional<NotificationMessage> claimNextDue(Instant now) {
    return jdbc.sql(CLAIM_NEXT_DUE)
        .param("now", utc(now))
        .query((rs, row) -> message(rs))
        .optional();
  }

  @Override
  public void markSent(UUID id, int attempts, String providerMessageId, Instant sentAt) {
    jdbc.sql(
            """
            UPDATE notification_messages
            SET status = 'SENT', attempts = :attempts, sent_at = :sentAt,
                provider_message_id = :providerMessageId, last_error = NULL
            WHERE id = :id""")
        .param("attempts", attempts)
        .param("sentAt", utc(sentAt))
        .param("providerMessageId", providerMessageId)
        .param("id", id)
        .update();
  }

  @Override
  public void markRetry(UUID id, int attempts, Instant nextAttemptAt, String lastError) {
    jdbc.sql(
            """
            UPDATE notification_messages
            SET attempts = :attempts, next_attempt_at = :nextAttemptAt, last_error = :lastError
            WHERE id = :id""")
        .param("attempts", attempts)
        .param("nextAttemptAt", utc(nextAttemptAt))
        .param("lastError", lastError)
        .param("id", id)
        .update();
  }

  @Override
  public void markFailed(UUID id, int attempts, String failureReason, String lastError) {
    jdbc.sql(
            """
            UPDATE notification_messages
            SET status = 'FAILED', attempts = :attempts, failure_reason = :failureReason,
                last_error = :lastError
            WHERE id = :id""")
        .param("attempts", attempts)
        .param("failureReason", failureReason)
        .param("lastError", lastError)
        .param("id", id)
        .update();
  }

  @Override
  public void markDispatchError(UUID id, String lastError) {
    jdbc.sql(
            """
            UPDATE notification_messages
            SET status = 'FAILED', failure_reason = 'DISPATCH_ERROR', last_error = :lastError
            WHERE id = :id AND status = 'PENDING'""")
        .param("lastError", lastError)
        .param("id", id)
        .update();
  }

  @Override
  public int deleteForAppointments(Collection<UUID> appointmentIds) {
    if (appointmentIds.isEmpty()) {
      return 0;
    }
    return jdbc.sql("DELETE FROM notification_messages WHERE appointment_id IN (:appointmentIds)")
        .param("appointmentIds", appointmentIds)
        .update();
  }

  private static NotificationMessage message(ResultSet rs) throws SQLException {
    return new NotificationMessage(
        rs.getObject("id", UUID.class),
        rs.getObject("appointment_id", UUID.class),
        NotificationKind.valueOf(rs.getString("kind")),
        NotificationChannel.valueOf(rs.getString("channel")),
        rs.getString("recipient"),
        rs.getString("subject"),
        rs.getString("body"),
        NotificationStatus.valueOf(rs.getString("status")),
        rs.getInt("attempts"),
        instant(rs, "next_attempt_at"),
        rs.getString("last_error"),
        rs.getString("failure_reason"),
        rs.getObject("sent_at", OffsetDateTime.class) == null ? null : instant(rs, "sent_at"),
        rs.getString("correlation_id"),
        instant(rs, "created_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
