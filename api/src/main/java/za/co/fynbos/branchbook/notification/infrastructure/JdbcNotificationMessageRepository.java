package za.co.fynbos.branchbook.notification.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
      VALUES (:id, :appointmentId, :kind, :channel, :recipient, NULL, :body, :status, :attempts,
              :nextAttemptAt, :failureReason, :sentAt, :correlationId, :createdAt)""";

  private static final String FOR_APPOINTMENT =
      """
      SELECT id, appointment_id, kind, channel, recipient, body, status, attempts, next_attempt_at,
             failure_reason, sent_at, correlation_id, created_at
      FROM notification_messages
      WHERE appointment_id = :appointmentId
      ORDER BY created_at, id""";

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

  private static NotificationMessage message(ResultSet rs) throws SQLException {
    return new NotificationMessage(
        rs.getObject("id", UUID.class),
        rs.getObject("appointment_id", UUID.class),
        NotificationKind.valueOf(rs.getString("kind")),
        NotificationChannel.valueOf(rs.getString("channel")),
        rs.getString("recipient"),
        rs.getString("body"),
        NotificationStatus.valueOf(rs.getString("status")),
        rs.getInt("attempts"),
        instant(rs, "next_attempt_at"),
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
