package za.co.fynbos.branchbook.booking.application;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One message about an appointment, as its response shows it. The values are plain strings, so the
 * booking module never depends on the notification module's types.
 *
 * @param id the message's id
 * @param channel {@code SMS}, or {@code EMAIL} if the e-mail channel is added
 * @param kind {@code CONFIRMATION} or {@code CANCELLATION}
 * @param status {@code PENDING}, {@code SENT} or {@code FAILED}
 * @param attempts how many times delivery has been tried, from 0 to 5
 * @param sentAt when the gateway accepted it, or {@code null} until then
 * @param failureReason why it failed, such as {@code DELIVERY_FAILED}, or {@code null}
 * @param recipientMasked where it goes, masked, for example {@code 082 *** 0101}
 * @param body the text of the message
 */
public record NotificationSummary(
    UUID id,
    String channel,
    String kind,
    String status,
    int attempts,
    @Nullable Instant sentAt,
    @Nullable String failureReason,
    String recipientMasked,
    String body) {}
