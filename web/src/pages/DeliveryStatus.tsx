/**
 * Where an SMS has got to: Sending, Sent at a time, Not delivered, or Not sent when a cancellation stopped a
 * confirmation before it went out, which is not a failure.
 *
 * The status sits in a polite live region that is on the page from the start, so when polling moves it from
 * Sending to Sent, a screen reader says so without the customer looking for it.
 *
 * @packageDocumentation
 */
import { isSupersededByCancellation, type Notification } from '../api/queries';
import { formatTime } from '../lib/time';
import { Badge } from '../ui/Badge';
import { CheckIcon, CrossIcon, PendingIcon } from '../ui/icons';
import styles from './DeliveryStatus.module.css';

/** Props for {@link DeliveryStatus}. */
export interface DeliveryStatusProps {
  /** The message whose status to show. */
  notification: Notification;
}

/** The delivery status badge, in a polite live region. */
export function DeliveryStatus({ notification }: DeliveryStatusProps) {
  return (
    <span role="status" className={styles.status}>
      {notification.status === 'PENDING' && (
        <Badge tone="pending" size="large">
          <PendingIcon size={18} className={styles.spinner} />
          Sending
        </Badge>
      )}
      {notification.status === 'SENT' && (
        <Badge tone="sent" size="large">
          <CheckIcon size={18} />
          {notification.sentAt === null ? 'Sent' : `Sent at ${formatTime(notification.sentAt)}`}
        </Badge>
      )}
      {isSupersededByCancellation(notification) && (
        <Badge tone="neutral" size="large">
          <CrossIcon size={18} />
          Not sent
        </Badge>
      )}
      {notification.status === 'FAILED' && !isSupersededByCancellation(notification) && (
        <Badge tone="failed" size="large">
          <CrossIcon size={18} />
          Not delivered
        </Badge>
      )}
    </span>
  );
}
