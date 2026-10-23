-- V2: the schema. Eight tables, and every rule that the database itself enforces.
--
-- Every constraint is named, because the names are a contract: the application maps a violation to an API error by
-- the constraint's name, never by the message text, and SchemaConstraintIT proves that each one fires.
--
-- Conventions:
-- - Every instant is timestamptz (stored in UTC). Trading hours are wall-clock time values, converted to instants only
--   through branches.timezone.
-- - Enumerations are varchar with a CHECK, and fixed-format codes are varchar with a pattern CHECK, never char(n).
--   varchar(n) already limits the length, so no CHECK repeats it.
-- - DEFAULT now() serves seed scripts and hand-written inserts only. The application always writes the time from its
--   injected Clock, so tests control time.
-- - Ownership (docs/architecture.md, section 3): the catalogue module owns the first five tables and never changes
--   them at runtime; booking owns appointments and idempotency_keys; notification owns notification_messages.

CREATE TABLE branches (
    id                      uuid         NOT NULL,
    code                    varchar(8)   NOT NULL,
    name                    varchar(60)  NOT NULL,
    sms_name                varchar(20)  NOT NULL,
    address_line1           varchar(120) NOT NULL,
    suburb                  varchar(60)  NOT NULL,
    city                    varchar(60)  NOT NULL,
    province                varchar(20)  NOT NULL,
    postal_code             varchar(4)   NOT NULL,
    timezone                varchar(40)  NOT NULL DEFAULT 'Africa/Johannesburg',
    open_on_public_holidays boolean      NOT NULL DEFAULT false,
    active                  boolean      NOT NULL DEFAULT true,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT branches_pkey PRIMARY KEY (id),
    CONSTRAINT branches_code_key UNIQUE (code),
    CONSTRAINT branches_code_format CHECK (code ~ '^[A-Z]{2,8}$'),
    -- The confirmation SMS names the branch, so its short name must be plain GSM-7 text (BR-18).
    CONSTRAINT branches_sms_name_gsm7 CHECK (sms_name ~ '^[A-Za-z0-9 ''-]+$'),
    CONSTRAINT branches_province_check CHECK (province IN ('Eastern Cape', 'Free State', 'Gauteng', 'KwaZulu-Natal',
        'Limpopo', 'Mpumalanga', 'North West', 'Northern Cape', 'Western Cape')),
    CONSTRAINT branches_postal_code_format CHECK (postal_code ~ '^[0-9]{4}$')
);

CREATE TABLE branch_operating_hours (
    branch_id uuid     NOT NULL,
    weekday   smallint NOT NULL,
    opens_at  time     NOT NULL,
    closes_at time     NOT NULL,
    CONSTRAINT branch_operating_hours_pkey PRIMARY KEY (branch_id, weekday),
    CONSTRAINT branch_operating_hours_branch_fkey FOREIGN KEY (branch_id) REFERENCES branches (id),
    -- ISO weekdays, numbered as java.time.DayOfWeek does: 1 is Monday, 7 is Sunday. No row means closed that day.
    CONSTRAINT branch_operating_hours_weekday_check CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT branch_operating_hours_open_before_close CHECK (opens_at < closes_at)
);

CREATE TABLE services (
    id               uuid         NOT NULL,
    code             varchar(12)  NOT NULL,
    name             varchar(60)  NOT NULL,
    sms_name         varchar(18)  NOT NULL,
    description      varchar(300) NOT NULL,
    duration_minutes smallint     NOT NULL,
    documents_needed text[]       NOT NULL,
    sort_order       smallint     NOT NULL,
    active           boolean      NOT NULL DEFAULT true,
    CONSTRAINT services_pkey PRIMARY KEY (id),
    CONSTRAINT services_code_key UNIQUE (code),
    CONSTRAINT services_code_format CHECK (code ~ '^[A-Z]{2,12}$'),
    CONSTRAINT services_sms_name_gsm7 CHECK (sms_name ~ '^[A-Za-z0-9 ''-]+$'),
    -- Slots step by the service's duration from opening time (BR-1), so durations stay on the quarter hour.
    CONSTRAINT services_duration_grid CHECK (duration_minutes BETWEEN 15 AND 120 AND duration_minutes % 15 = 0),
    -- Every service tells the customer what to bring: one to eight documents, none of them NULL.
    CONSTRAINT services_documents_needed_count CHECK (cardinality(documents_needed) BETWEEN 1 AND 8
        AND array_position(documents_needed, NULL) IS NULL)
);

-- Which services each branch offers, and how many customers it can see for each at the same time.
CREATE TABLE branch_services (
    branch_id  uuid     NOT NULL,
    service_id uuid     NOT NULL,
    capacity   smallint NOT NULL,
    CONSTRAINT branch_services_pkey PRIMARY KEY (branch_id, service_id),
    CONSTRAINT branch_services_branch_fkey FOREIGN KEY (branch_id) REFERENCES branches (id),
    CONSTRAINT branch_services_service_fkey FOREIGN KEY (service_id) REFERENCES services (id),
    -- Capacity is the number of seats: consultants who can see customers for this service at once.
    CONSTRAINT branch_services_capacity_check CHECK (capacity BETWEEN 1 AND 20)
);

CREATE TABLE public_holidays (
    holiday_date date        NOT NULL,
    name         varchar(80) NOT NULL,
    source       varchar(12) NOT NULL,
    CONSTRAINT public_holidays_pkey PRIMARY KEY (holiday_date),
    -- SCHEDULE_1: a holiday in Schedule 1 of the Public Holidays Act 36 of 1994. PROCLAMATION: a day the President
    -- declared under section 2A. The Monday after a Sunday holiday (section 2(1)) is derived in code, never stored.
    CONSTRAINT public_holidays_source_check CHECK (source IN ('SCHEDULE_1', 'PROCLAMATION'))
);

CREATE TABLE appointments (
    id                  uuid         NOT NULL,
    reference           varchar(13)  NOT NULL,
    branch_id           uuid         NOT NULL,
    service_id          uuid         NOT NULL,
    seat_no             smallint     NOT NULL,
    starts_at           timestamptz  NOT NULL,
    ends_at             timestamptz  NOT NULL,
    status              varchar(12)  NOT NULL DEFAULT 'CONFIRMED',
    customer_first_name varchar(60)  NOT NULL,
    customer_last_name  varchar(60)  NOT NULL,
    customer_mobile     varchar(16)  NOT NULL,
    customer_email      varchar(254),
    cancelled_at        timestamptz,
    checked_in_at       timestamptz,
    completed_at        timestamptz,
    no_show_at          timestamptz,
    version             integer      NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT appointments_pkey PRIMARY KEY (id),
    CONSTRAINT appointments_reference_key UNIQUE (reference),
    -- APT- and two groups of four from a 31-character alphabet: A to Z and 2 to 9 without I, L, O, 0 and 1, which are
    -- easily misread when a reference is read out or typed from an SMS (BR-11).
    CONSTRAINT appointments_reference_format CHECK (reference ~ '^APT-[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}$'),
    -- A branch can be booked only for a service it offers (BR-6): the pair must exist in branch_services.
    CONSTRAINT appointments_branch_service_fkey FOREIGN KEY (branch_id, service_id)
        REFERENCES branch_services (branch_id, service_id),
    CONSTRAINT appointments_seat_no_check CHECK (seat_no BETWEEN 1 AND 20),
    CONSTRAINT appointments_ends_after_starts CHECK (ends_at > starts_at),
    CONSTRAINT appointments_status_check CHECK (status IN ('CONFIRMED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED',
        'NO_SHOW')),
    -- Names are stored as the application normalised them: not empty, no control characters anywhere, and no space at
    -- either end. The spaces and controls are listed explicitly as well as by class, so the check does not depend on
    -- the database's locale: U+0080-U+009F (C1 controls), U+00A0, U+1680, U+2000-U+200B, U+2028, U+2029, U+202F,
    -- U+205F, U+3000 and U+FEFF, the Unicode spaces and invisible characters that locales leave out of [:space:].
    CONSTRAINT appointments_customer_names_trimmed CHECK (
        customer_first_name ~ ('^[^[:space:][:cntrl:]\u0080-\u00a0\u1680\u2000-\u200b\u2028\u2029\u202f\u205f\u3000\ufeff]'
            || '([^[:cntrl:]\u0080-\u009f]*'
            || '[^[:space:][:cntrl:]\u0080-\u00a0\u1680\u2000-\u200b\u2028\u2029\u202f\u205f\u3000\ufeff])?$')
        AND customer_last_name ~ ('^[^[:space:][:cntrl:]\u0080-\u00a0\u1680\u2000-\u200b\u2028\u2029\u202f\u205f\u3000\ufeff]'
            || '([^[:cntrl:]\u0080-\u009f]*'
            || '[^[:space:][:cntrl:]\u0080-\u00a0\u1680\u2000-\u200b\u2028\u2029\u202f\u205f\u3000\ufeff])?$')),
    -- A South African mobile number in E.164 form: +27, then a 6x, 7x or 81-85 prefix, then seven digits (BR-19).
    CONSTRAINT appointments_customer_mobile_e164 CHECK (customer_mobile ~ '^\+27(6[0-9]|7[0-9]|8[1-5])[0-9]{7}$'),
    -- Each lifecycle timestamp is present exactly when the status says the event happened (BR-15).
    CONSTRAINT appointments_cancelled_at_consistent CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT appointments_checked_in_at_consistent CHECK ((status IN ('CHECKED_IN', 'COMPLETED'))
        = (checked_in_at IS NOT NULL)),
    CONSTRAINT appointments_completed_at_consistent CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT appointments_no_show_at_consistent CHECK ((status = 'NO_SHOW') = (no_show_at IS NOT NULL))
);

-- The invariant of the whole system (BR-8): no seat of a service at a branch is ever booked twice for overlapping
-- times, whichever code path writes the row.
--
-- The '[)' ranges let 09:00-09:30 and 09:30-10:00 share a seat. The partial predicate frees the seat of a
-- cancelled, completed or no-show appointment without deleting the row.
--
-- The database does not cap seat_no at the branch's capacity; it only bounds it to 1-20 (appointments_seat_no_check).
-- Choosing a seat between 1 and the capacity is the booking transaction's job: it takes the lowest free seat while
-- holding the branch_services row lock (BR-7).
--
-- Why an exclusion constraint rather than UNIQUE (branch_id, service_id, seat_no, starts_at):
-- 1. The customer rule below must compare time ranges of services with different durations, which only an overlap
--    operator can do, so both rules use the same mechanism.
-- 2. A unique index is correct only while every slot sits on one grid. Nothing enforces that, and a later change to
--    a branch's opening hours would silently break it.
-- 3. The production design moves to a branch-wide pool of consultants, where services of different lengths share
--    seats: EXCLUDE (branch_id WITH =, seat_no WITH =, range WITH &&). Only an overlap constraint carries over.
ALTER TABLE appointments ADD CONSTRAINT appointments_seat_no_overlap EXCLUDE USING gist (
    branch_id WITH =,
    service_id WITH =,
    seat_no WITH =,
    tstzrange(starts_at, ends_at, '[)') WITH &&
) WHERE (status IN ('CONFIRMED', 'CHECKED_IN'));

-- One person, one place at a time (BR-9): a mobile number never holds two active appointments with overlapping times,
-- at any branch or service. The booking transaction checks this first, under a per-mobile lock, to answer with a
-- clear 409; this constraint is the backstop for any path that skips the check.
ALTER TABLE appointments ADD CONSTRAINT appointments_customer_no_overlap EXCLUDE USING gist (
    customer_mobile WITH =,
    tstzrange(starts_at, ends_at, '[)') WITH &&
) WHERE (status IN ('CONFIRMED', 'CHECKED_IN'));

-- Serves the per-customer checks of the booking transaction (BR-9, BR-10). It is deliberately not unique: "one
-- upcoming appointment per service" depends on the current time, and an index predicate cannot use now(), which is
-- not immutable. So the rule is checked under a per-mobile advisory lock, and this index makes that check a lookup.
CREATE INDEX ix_appointments_customer_active ON appointments (customer_mobile, service_id)
    WHERE status IN ('CONFIRMED', 'CHECKED_IN');

-- Availability counts and the lowest-free-seat query.
CREATE INDEX ix_appointments_slot_lookup ON appointments (branch_id, service_id, starts_at)
    WHERE status IN ('CONFIRMED', 'CHECKED_IN');

-- Makes booking retries safe (BR-12). The key row is the first write of the booking transaction, so a second request
-- with the same key waits for the first and then replays its result instead of booking twice.
CREATE TABLE idempotency_keys (
    key             uuid        NOT NULL,
    request_hash    varchar(64) NOT NULL,
    appointment_id  uuid,
    response_status smallint,
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    CONSTRAINT idempotency_keys_pkey PRIMARY KEY (key),
    CONSTRAINT idempotency_keys_appointment_fkey FOREIGN KEY (appointment_id) REFERENCES appointments (id),
    -- A SHA-256 hash of the request, in lower-case hex, so a reused key with different details is detected.
    CONSTRAINT idempotency_keys_request_hash_format CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    -- Only successful bookings are remembered; a failed booking rolls its key row back with it.
    CONSTRAINT idempotency_keys_response_status_check CHECK (response_status IS NULL OR response_status = 201),
    CONSTRAINT idempotency_keys_completed_consistent CHECK ((appointment_id IS NULL) = (response_status IS NULL)),
    CONSTRAINT idempotency_keys_expiry_after_creation CHECK (expires_at > created_at)
);

-- The hourly purge of expired keys.
CREATE INDEX ix_idempotency_keys_expires ON idempotency_keys (expires_at);

-- The transactional outbox (ADR-0003): every confirmation and cancellation message, written in the same transaction
-- as the booking or cancellation, then delivered by the dispatcher.
CREATE TABLE notification_messages (
    id                  uuid         NOT NULL,
    appointment_id      uuid         NOT NULL,
    kind                varchar(16)  NOT NULL,
    channel             varchar(8)   NOT NULL,
    -- An E.164 number or an e-mail address. Never logged.
    recipient           varchar(254) NOT NULL,
    subject             varchar(160),
    body                text         NOT NULL,
    status              varchar(8)   NOT NULL DEFAULT 'PENDING',
    attempts            smallint     NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz  NOT NULL DEFAULT now(),
    -- Operator-facing text only: the gateway's error, or MARK_FAILED.
    last_error          varchar(500),
    -- The stable code the API exposes as failureReason.
    failure_reason      varchar(32),
    provider_message_id varchar(64),
    -- The request's X-Correlation-Id, or 'sys-' and a UUID for work that started outside a request.
    correlation_id      varchar(64)  NOT NULL,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    sent_at             timestamptz,
    CONSTRAINT notification_messages_pkey PRIMARY KEY (id),
    CONSTRAINT notification_messages_appointment_fkey FOREIGN KEY (appointment_id) REFERENCES appointments (id),
    CONSTRAINT notification_messages_kind_check CHECK (kind IN ('CONFIRMATION', 'CANCELLATION')),
    CONSTRAINT notification_messages_channel_check CHECK (channel IN ('SMS', 'EMAIL')),
    CONSTRAINT notification_messages_subject_email_only CHECK ((channel = 'EMAIL') = (subject IS NOT NULL)),
    -- A coarse guard only. The one-segment rule (160 GSM-7 characters, BR-18) lives in the message templates, so a
    -- template change can never make a booking fail.
    CONSTRAINT notification_messages_sms_len CHECK (channel <> 'SMS' OR char_length(body) <= 320),
    CONSTRAINT notification_messages_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    -- The fifth failed attempt is final (BR-17).
    CONSTRAINT notification_messages_attempts_check CHECK (attempts BETWEEN 0 AND 5),
    CONSTRAINT notification_messages_failure_reason_check CHECK (failure_reason IN ('DELIVERY_FAILED',
        'SUPERSEDED_BY_CANCELLATION', 'RECIPIENT_DAILY_CAP', 'DISPATCH_ERROR')),
    CONSTRAINT notification_messages_failure_reason_consistent CHECK ((status = 'FAILED')
        = (failure_reason IS NOT NULL)),
    CONSTRAINT notification_messages_sent_at_consistent CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

-- The dispatcher's claim query: due messages that are still pending.
CREATE INDEX ix_notification_messages_due ON notification_messages (next_attempt_at) WHERE status = 'PENDING';
-- An appointment's messages, for its delivery status and for superseding an unsent confirmation.
CREATE INDEX ix_notification_messages_appointment ON notification_messages (appointment_id);
-- The simulated inbox, newest first.
CREATE INDEX ix_notification_messages_created ON notification_messages (created_at DESC);
