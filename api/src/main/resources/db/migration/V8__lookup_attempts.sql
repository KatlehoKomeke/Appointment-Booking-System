-- V8: the lookup limiter's counts (BR-13).
--
-- Five wrong lookups of a reference in an hour block it. Until V8 the limiter counted them in memory, per instance, so
-- two instances would each have allowed five of their own. The counts move here, where every instance reads and locks
-- the same row, and they survive a restart.
--
-- One row per reference with an open window: when it opened, when it closes (opens_at plus
-- branchbook.booking.lookup-window), the wrong lookups in it, the admitted lookups whose digits are still being
-- compared, and the lease of the newest of those (held_until, opens_at plus 30 seconds at most). The lookups in flight
-- count towards the limit only until the lease runs out, so a lookup that is never settled, because its instance
-- crashed or its settle failed, holds a place for 30 seconds and no longer. Every instant comes from the application's
-- Clock (BR-20). The reference is stored as appointments stores it; a reference alone opens nothing, and a row
-- outlives a deleted appointment by at most its window. There is deliberately no foreign key: the row must survive the
-- appointment's deletion, because a deleted reference is counted exactly like one never issued (BR-13, BR-14). No
-- name, number or appointment id, so no row leads to a booking.
--
-- The limiter locks a reference's row (FOR UPDATE) for each count, in a short transaction of its own, so lookups that
-- arrive together, on any instance, are counted one at a time. A window that has closed is ignored and replaced at the
-- next lookup; an hourly job deletes rows whose window has closed, so the table holds about an hour of wrongly
-- looked-up references.
-- Ownership: the booking module, which also owns appointments and idempotency_keys (V2).

CREATE TABLE lookup_attempts (
    reference  varchar(13) NOT NULL,
    opens_at   timestamptz NOT NULL,
    closes_at  timestamptz NOT NULL,
    held_until timestamptz NOT NULL,
    failures   smallint    NOT NULL,
    in_flight  smallint    NOT NULL,
    CONSTRAINT lookup_attempts_pkey PRIMARY KEY (reference),
    -- The same pattern as appointments_reference_format (BR-11).
    CONSTRAINT lookup_attempts_reference_format CHECK (reference ~ '^APT-[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}$'),
    CONSTRAINT lookup_attempts_window_order CHECK (opens_at < closes_at),
    -- A lease is granted by an admission, and the first admission is what opens the window.
    CONSTRAINT lookup_attempts_lease_after_open CHECK (held_until > opens_at),
    CONSTRAINT lookup_attempts_counts_not_negative CHECK (failures >= 0 AND in_flight >= 0)
);

-- The hourly purge of windows that have closed.
CREATE INDEX ix_lookup_attempts_closes ON lookup_attempts (closes_at);
