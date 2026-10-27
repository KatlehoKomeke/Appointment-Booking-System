-- V5: the per-recipient daily cap's count (BR-17).
--
-- Where branchbook.sms.recipient-daily-cap is set, every confirmation and cancellation first counts the SMS its
-- number was written in the last 24 hours. This index lets that count read one number's recent rows instead of
-- every message written that day. The retention purge keeps the table to 48 hours in the hosted demo, so the
-- index stays small.
CREATE INDEX ix_notification_messages_recipient ON notification_messages (recipient, created_at);
