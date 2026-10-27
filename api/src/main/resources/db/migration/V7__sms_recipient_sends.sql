-- V7: the per-recipient daily cap's ledger (BR-17).
--
-- Where branchbook.sms.recipient-daily-cap is above 0, the cap counted a number's rows in notification_messages. From
-- V6 a cancel deletes its booking's confirmations, and the dispatcher deletes a cancellation once it is final, so those
-- rows no longer last the 24 hours the cap looks back over: a number could be booked and cancelled in turn, and sent an
-- SMS each time, without ever reaching the cap. This table remembers each SMS the cap counts for those 24 hours,
-- whatever becomes of the message.
--
-- Each row is one SMS written to a number outside the published demo block while the cap is on: the number's keyed
-- fingerprint and the instant the SMS was written, from the application's Clock. The fingerprint is HMAC-SHA256 of the
-- number's E.164 form under branchbook.sms.recipient-cap-key. A plain hash would not do: the mobile pattern
-- (appointments_customer_mobile_e164) allows only 250 million numbers, few enough to hash every one and match a row
-- back to its number, which the key prevents. There is no number, name, text, reference or foreign key, so no row
-- leads back to a booking or a message.
--
-- The outbox counts a number's rows and writes its new one in the booking or cancel transaction, so a rolled-back
-- booking leaves none. An hourly job deletes the rows once they are 24 hours old, so the table holds about a day.
--
-- There is no primary key: two SMS to one number can be written at the same instant, and each must count. The column
-- is varchar(64) with a pattern CHECK, never char(64), as V2's conventions ask, like idempotency_keys.request_hash.
-- Ownership: the notification module, which also owns notification_messages (V2).

CREATE TABLE sms_recipient_sends (
    recipient_fingerprint varchar(64) NOT NULL,
    written_at            timestamptz NOT NULL,
    -- HMAC-SHA256 in lower-case hex: never the number, in any form.
    CONSTRAINT sms_recipient_sends_fingerprint_format CHECK (recipient_fingerprint ~ '^[0-9a-f]{64}$')
);

-- The cap's count: one number's rows written in the last 24 hours.
CREATE INDEX ix_sms_recipient_sends_fingerprint ON sms_recipient_sends (recipient_fingerprint, written_at);

-- The hourly purge of rows 24 hours old.
CREATE INDEX ix_sms_recipient_sends_written ON sms_recipient_sends (written_at);

-- V5's index served the cap's count over notification_messages, which this ledger replaces, so no query uses it now.
DROP INDEX ix_notification_messages_recipient;
