-- V6: a cancel deletes the booking at once (BR-14). A cancelled booking is personal data with no further use, so the
-- cancel transaction destroys it instead of keeping it CANCELLED. Only two things outlive it:
-- - its cancellation SMS, which still holds the number and the text until the dispatcher has sent it or given up on
--   it, and then deletes it (BR-16, BR-17);
-- - its Idempotency-Key, which holds no name or number, only an unkeyed SHA-256 of the request's details (so a
--   guess of them can be confirmed against it: pseudonymised, not anonymous), and keeps its 24 hours, so a retry of
--   the booking is refused rather than booked again (BR-12).
--
-- The cancel deletes the appointment's confirmations, writes the cancellation SMS against the appointment, and then
-- deletes the appointment, with its version checked. The two foreign keys below detach what is kept in that same
-- statement, so the whole change commits together or not at all.
--
-- Every constraint keeps its name, because the names are a contract (see V2): a dropped constraint is re-created
-- under the name it had.

-- A cancellation SMS has no appointment once the cancel has deleted it.
ALTER TABLE notification_messages ALTER COLUMN appointment_id DROP NOT NULL;

ALTER TABLE notification_messages DROP CONSTRAINT notification_messages_appointment_fkey;
ALTER TABLE notification_messages ADD CONSTRAINT notification_messages_appointment_fkey
    FOREIGN KEY (appointment_id) REFERENCES appointments (id) ON DELETE SET NULL;

-- Only a cancellation may outlive its appointment. A confirmation goes with it, so one with no appointment would
-- describe a booking that no longer exists; this rule also fails the appointment's delete if one were left behind.
ALTER TABLE notification_messages ADD CONSTRAINT notification_messages_detached_cancellation_only
    CHECK (appointment_id IS NOT NULL OR kind = 'CANCELLATION');

ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_appointment_fkey;
ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_appointment_fkey
    FOREIGN KEY (appointment_id) REFERENCES appointments (id) ON DELETE SET NULL;

-- An appointment id implies a completed key. A completed key may outlive the appointment it booked, which a cancel
-- deleted, so a retry of that booking is answered 422 and never books again; a key still in progress has neither.
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_completed_consistent;
ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_completed_consistent
    CHECK (appointment_id IS NULL OR response_status IS NOT NULL);
