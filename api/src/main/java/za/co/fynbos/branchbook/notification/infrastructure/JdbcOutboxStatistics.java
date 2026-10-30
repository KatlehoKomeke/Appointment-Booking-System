package za.co.fynbos.branchbook.notification.infrastructure;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import za.co.fynbos.branchbook.notification.application.OutboxStatistics;

/**
 * Reads the outbox's alarm figures from {@code notification_messages} in one scan, with no instant
 * of the database's own: the age is worked out by the caller, from the application's clock.
 */
@Repository
class JdbcOutboxStatistics implements OutboxStatistics {

  private static final String READ =
      """
      SELECT min(created_at) FILTER (WHERE status = 'PENDING') AS oldest_pending,
             count(*) FILTER (WHERE status = 'FAILED') AS failed
        FROM notification_messages""";

  private final JdbcClient jdbc;

  JdbcOutboxStatistics(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Snapshot read() {
    return jdbc.sql(READ)
        .query(
            (rs, row) -> {
              OffsetDateTime oldest = rs.getObject("oldest_pending", OffsetDateTime.class);
              return new Snapshot(oldest == null ? null : oldest.toInstant(), rs.getLong("failed"));
            })
        .single();
  }
}
