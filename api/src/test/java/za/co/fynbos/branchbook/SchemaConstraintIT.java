package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.booking.domain.AppointmentStatus;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.SeedData;

/**
 * Proves, with raw SQL that bypasses the application entirely, that every named constraint in the
 * schema rejects what it is meant to reject.
 *
 * <p>Each case runs in its own transaction and is rolled back, so the database is left as Flyway
 * created it. Violations are identified by the constraint name and SQLSTATE that PostgreSQL
 * reports, never by the message text, which is also how the application will translate them. {@link
 * #everyNamedConstraintHasACase()} fails if a constraint is added without a case here. {@link
 * #rowsTheRulesAllowAreAccepted} covers the other side for the rules most likely to be drawn too
 * tightly: the two overlap constraints, the name check and the reference alphabet.
 */
@IntegrationTest
class SchemaConstraintIT {

  private static final String EXCLUSION = "23P01";
  private static final String UNIQUE = "23505";
  private static final String FOREIGN_KEY = "23503";
  private static final String CHECK = "23514";

  private static final String APPOINTMENT_ID = "'a0000000-0000-4000-8000-000000000001'";
  private static final String TEST_ID = "'b0000000-0000-4000-8000-000000000001'";
  private static final String UNKNOWN_ID = "'ffffffff-ffff-4fff-8fff-ffffffffffff'";

  /** Synthetic numbers from the demo range in SECURITY.md (082 555 01xx). */
  private static final String FIRST_MOBILE = "'+27825550101'";

  private static final String SECOND_MOBILE = "'+27825550102'";

  @Autowired private DataSource dataSource;

  @Autowired private JdbcTemplate jdbc;

  @ParameterizedTest(name = "{0}")
  @MethodSource("violations")
  void rawInsertViolatingEachNamedConstraintIsRejected(Violation violation) throws SQLException {
    PSQLException rejected = rejectionOf(violation.statements());

    assertThat(rejected.getServerErrorMessage()).isNotNull();
    assertThat(rejected.getServerErrorMessage().getConstraint()).isEqualTo(violation.constraint());
    assertThat(rejected.getSQLState()).isEqualTo(violation.sqlState());
  }

  /**
   * The other side of the rules: appointments the overlap rules allow together, and the names and
   * references the format checks must let through, are accepted.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("acceptances")
  void rowsTheRulesAllowAreAccepted(Acceptance acceptance) {
    assertThatNoException().isThrownBy(() -> runAndRollBack(acceptance.statements()));
  }

  /** BR-15: the status CHECK allows exactly the statuses {@link AppointmentStatus} defines. */
  @Test
  void statusCheckAllowsExactlyTheJavaStatuses() {
    String definition =
        jdbc.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conname = 'appointments_status_check'",
            String.class);

    assertThat(statusesIn(definition))
        .containsExactlyInAnyOrderElementsOf(
            Stream.of(AppointmentStatus.values()).map(Enum::name).toList());
  }

  /**
   * BR-19: the mobile CHECK uses exactly {@link MobileNumber#E164_PATTERN}, so every number the
   * application accepts can be stored, and nothing else can.
   */
  @Test
  void mobileCheckMatchesMobileNumberPattern() {
    assertThat(regexIn("appointments_customer_mobile_e164", "customer_mobile"))
        .isEqualTo(MobileNumber.E164_PATTERN);
  }

  /** BR-11: the reference CHECK uses exactly {@link AppointmentReference#PATTERN}. */
  @Test
  void referenceCheckMatchesAppointmentReferencePattern() {
    assertThat(regexIn("appointments_reference_format", "reference"))
        .isEqualTo(AppointmentReference.PATTERN);
  }

  /** BR-8: the seat rule holds against any code path, with no application lock involved. */
  @Test
  void overlappingSeatIsRejectedEvenWithoutTheApplication() throws SQLException {
    PSQLException rejected =
        rejectionOf(
            List.of(
                appointment("APT-AAAA-AAAA").insert(),
                appointment("APT-BBBB-BBBB")
                    .with("customer_mobile", SECOND_MOBILE)
                    .with("starts_at", "'2026-10-27 09:10+02'")
                    .with("ends_at", "'2026-10-27 09:25+02'")
                    .insert()));

    assertThat(rejected.getServerErrorMessage().getConstraint())
        .isEqualTo("appointments_seat_no_overlap");
  }

  /**
   * BR-19: the exception the application would log does not quote the conflicting mobile number.
   */
  @Test
  void violationMessagesNeverQuoteTheConflictingValues() throws SQLException {
    PSQLException rejected =
        rejectionOf(
            List.of(
                appointment("APT-AAAA-AAAA").insert(),
                appointment("APT-BBBB-BBBB")
                    .with("branch_id", literal(SeedData.BELLVILLE))
                    .insert()));

    assertThat(rejected.getServerErrorMessage().getConstraint())
        .isEqualTo("appointments_customer_no_overlap");
    assertThat(rejected.getMessage()).doesNotContain("825550101");
  }

  /**
   * The statuses that hold a seat are spelled in four places in the schema: both exclusion
   * constraints and both partial indexes. All four must match {@link AppointmentStatus#ACTIVE}.
   */
  @Test
  void activeStatusesMatchSchemaPredicate() {
    Set<String> active =
        AppointmentStatus.ACTIVE.stream().map(Enum::name).collect(Collectors.toSet());
    List<String> predicates =
        jdbc.queryForList(
            """
            SELECT pg_get_constraintdef(oid) FROM pg_constraint
            WHERE conname IN ('appointments_seat_no_overlap', 'appointments_customer_no_overlap')
            UNION ALL
            SELECT pg_get_expr(indpred, indrelid) FROM pg_index
            WHERE indexrelid IN ('ix_appointments_customer_active'::regclass,
                                 'ix_appointments_slot_lookup'::regclass)""",
            String.class);

    assertThat(predicates).hasSize(4).allSatisfy(p -> assertThat(statusesIn(p)).isEqualTo(active));
  }

  /**
   * BR-14, V6: deleting an appointment keeps its cancellation SMS and its completed idempotency
   * key, each with no appointment, in the same statement; a key still in progress, which has no
   * appointment yet, is untouched.
   */
  @Test
  void deletingAnAppointmentDetachesItsCancellationAndItsKey() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute(bookedAppointment().insert());
        statement.execute(message().with("id", TEST_ID).with("kind", "'CANCELLATION'").insert());
        statement.execute(
            idempotencyKey()
                .with("key", TEST_ID)
                .with("appointment_id", APPOINTMENT_ID)
                .with("response_status", "201")
                .insert());
        statement.execute(idempotencyKey().insert());

        assertThat(statement.executeUpdate("DELETE FROM appointments WHERE id = " + APPOINTMENT_ID))
            .isOne();

        assertThat(rows(statement, "SELECT kind, appointment_id FROM notification_messages"))
            .containsExactly("CANCELLATION null");
        assertThat(
                rows(
                    statement,
                    "SELECT response_status, appointment_id FROM idempotency_keys"
                        + " ORDER BY response_status NULLS LAST"))
            .containsExactly("201 null", "null null");
      } finally {
        connection.rollback();
      }
    }
  }

  /** Primary keys, unique, check, foreign key and exclusion constraints: every one has a case. */
  @Test
  void everyNamedConstraintHasACase() {
    List<String> inSchema =
        jdbc.queryForList(
            """
            SELECT con.conname FROM pg_constraint con
            JOIN pg_class rel ON rel.oid = con.conrelid
            JOIN pg_namespace ns ON ns.oid = rel.relnamespace
            WHERE ns.nspname = current_schema()
              AND rel.relname <> 'flyway_schema_history'
              AND con.contype IN ('c', 'f', 'p', 'u', 'x')""",
            String.class);

    assertThat(violations().map(Violation::constraint).distinct())
        .containsExactlyInAnyOrderElementsOf(inSchema);
  }

  @Test
  void validRowsOfEveryTableAreAccepted() {
    assertThatNoException()
        .isThrownBy(
            () ->
                runAndRollBack(
                    List.of(
                        branch().insert(),
                        hours().insert(),
                        service().insert(),
                        branchService().insert(),
                        holiday().insert(),
                        bookedAppointment().insert(),
                        idempotencyKey().insert(),
                        message().insert())));
  }

  static Stream<Violation> violations() {
    String booked = bookedAppointment().insert();
    Row tenOClock =
        appointment("APT-DDDD-DDDD")
            .with("customer_mobile", SECOND_MOBILE)
            .with("starts_at", "'2026-10-27 10:00+02'")
            .with("ends_at", "'2026-10-27 10:15+02'");
    return Stream.of(
        // branches
        new Violation(
            "branches_pkey",
            UNIQUE,
            "the same id twice",
            branch().with("id", TEST_ID).insert(),
            branch().with("id", TEST_ID).with("code", "'TSU'").insert()),
        check("branches_code_format", "lower-case code", branch().with("code", "'san'")),
        new Violation(
            "branches_code_key",
            UNIQUE,
            "an existing code",
            branch().with("code", "'SAN'").insert()),
        check(
            "branches_sms_name_gsm7",
            "a character outside GSM-7",
            branch().with("sms_name", "'Café Central'")),
        check(
            "branches_province_check", "not a province", branch().with("province", "'Transvaal'")),
        check("branches_postal_code_format", "a letter", branch().with("postal_code", "'21A6'")),
        // branch_operating_hours
        new Violation(
            "branch_operating_hours_pkey",
            UNIQUE,
            "a second set of Monday hours",
            hours().with("weekday", "1").insert()),
        foreignKey(
            "branch_operating_hours_branch_fkey",
            "an unknown branch",
            hours().with("branch_id", UNKNOWN_ID)),
        check("branch_operating_hours_weekday_check", "day 8", hours().with("weekday", "8")),
        check(
            "branch_operating_hours_open_before_close",
            "closes before it opens",
            hours().with("opens_at", "'13:00'").with("closes_at", "'09:00'")),
        // services
        new Violation(
            "services_pkey",
            UNIQUE,
            "an existing id",
            service().with("id", literal(SeedData.FICA)).insert()),
        check("services_code_format", "lower-case code", service().with("code", "'test'")),
        new Violation(
            "services_code_key",
            UNIQUE,
            "an existing code",
            service().with("code", "'FICA'").insert()),
        check(
            "services_sms_name_gsm7",
            "a character outside GSM-7",
            service().with("sms_name", "'Café'")),
        check(
            "services_duration_grid",
            "not a quarter hour",
            service().with("duration_minutes", "20")),
        check(
            "services_documents_needed_count",
            "no documents",
            service().with("documents_needed", "ARRAY[]::text[]")),
        check(
            "services_documents_needed_count",
            "a NULL document",
            service().with("documents_needed", "ARRAY['Valid SA ID', NULL]")),
        // branch_services
        new Violation(
            "branch_services_pkey",
            UNIQUE,
            "a service the branch already offers",
            branchService().with("branch_id", literal(SeedData.SANDTON)).insert()),
        foreignKey(
            "branch_services_branch_fkey",
            "an unknown branch",
            branchService().with("branch_id", UNKNOWN_ID)),
        foreignKey(
            "branch_services_service_fkey",
            "an unknown service",
            branchService().with("service_id", UNKNOWN_ID)),
        check("branch_services_capacity_check", "no seats", branchService().with("capacity", "0")),
        // public_holidays
        new Violation(
            "public_holidays_pkey",
            UNIQUE,
            "an existing date",
            holiday().with("holiday_date", "'2026-12-16'").insert()),
        check(
            "public_holidays_source_check",
            "an unknown source",
            holiday().with("source", "'GAZETTE'")),
        // appointments
        new Violation(
            "appointments_pkey",
            UNIQUE,
            "the same id twice",
            booked,
            tenOClock.with("id", APPOINTMENT_ID).insert()),
        new Violation(
            "appointments_reference_key",
            UNIQUE,
            "the same reference twice",
            appointment("APT-AAAA-AAAA").insert(),
            tenOClock.with("reference", "'APT-AAAA-AAAA'").insert()),
        check(
            "appointments_reference_format", "lower case and zeros", appointment("APT-aaaa-0000")),
        check(
            "appointments_reference_format",
            "L, which is excluded from the alphabet",
            appointment("APT-LLLL-AAAA")),
        foreignKey(
            "appointments_branch_service_fkey",
            "a service the branch does not offer",
            appointment("APT-AAAA-AAAA")
                .with("branch_id", literal(SeedData.GQEBERHA))
                .with("service_id", literal(SeedData.HOME_LOAN))),
        check(
            "appointments_seat_no_check",
            "seat 0",
            appointment("APT-AAAA-AAAA").with("seat_no", "0")),
        check(
            "appointments_ends_after_starts",
            "no duration",
            appointment("APT-AAAA-AAAA").with("ends_at", "'2026-10-27 09:00+02'")),
        check(
            "appointments_status_check",
            "an unknown status",
            appointment("APT-AAAA-AAAA").with("status", "'PENDING'")),
        check(
            "appointments_customer_names_trimmed",
            "a leading space",
            appointment("APT-AAAA-AAAA").with("customer_first_name", "' Thandi'")),
        check(
            "appointments_customer_names_trimmed",
            "a leading tab",
            appointment("APT-AAAA-AAAA").with("customer_first_name", "E'\\tThandi'")),
        check(
            "appointments_customer_names_trimmed",
            "a trailing non-breaking space",
            appointment("APT-AAAA-AAAA").with("customer_last_name", "'Mokoena' || chr(160)")),
        check(
            "appointments_customer_names_trimmed",
            "a control character inside",
            appointment("APT-AAAA-AAAA").with("customer_last_name", "E'Mok\\noena'")),
        check(
            "appointments_customer_names_trimmed",
            "an empty name",
            appointment("APT-AAAA-AAAA").with("customer_first_name", "''")),
        check(
            "appointments_customer_names_trimmed",
            "a trailing space",
            appointment("APT-AAAA-AAAA").with("customer_first_name", "'Thandi '")),
        check(
            "appointments_customer_names_trimmed",
            "a trailing tab",
            appointment("APT-AAAA-AAAA").with("customer_last_name", "E'Mokoena\\t'")),
        check(
            "appointments_customer_names_trimmed",
            "a leading non-breaking space",
            appointment("APT-AAAA-AAAA").with("customer_last_name", "chr(160) || 'Mokoena'")),
        check(
            "appointments_customer_names_trimmed",
            "a trailing narrow no-break space, outside the locale's space class",
            appointment("APT-AAAA-AAAA").with("customer_last_name", "'Mokoena' || chr(8239)")),
        check(
            "appointments_customer_names_trimmed",
            "a C1 control character inside",
            appointment("APT-AAAA-AAAA").with("customer_first_name", "'Tha' || chr(133) || 'ndi'")),
        check(
            "appointments_customer_mobile_e164",
            "a landline",
            appointment("APT-AAAA-AAAA").with("customer_mobile", "'+27115550101'")),
        check(
            "appointments_cancelled_at_consistent",
            "CANCELLED without a time",
            appointment("APT-AAAA-AAAA").with("status", "'CANCELLED'")),
        check(
            "appointments_cancelled_at_consistent",
            "a cancellation time on a confirmed appointment",
            appointment("APT-AAAA-AAAA").with("cancelled_at", "'2026-10-26 10:00+02'")),
        check(
            "appointments_checked_in_at_consistent",
            "CHECKED_IN without a time",
            appointment("APT-AAAA-AAAA").with("status", "'CHECKED_IN'")),
        check(
            "appointments_checked_in_at_consistent",
            "a check-in time on a confirmed appointment",
            appointment("APT-AAAA-AAAA").with("checked_in_at", "'2026-10-27 08:55+02'")),
        check(
            "appointments_completed_at_consistent",
            "COMPLETED without a time",
            appointment("APT-AAAA-AAAA")
                .with("status", "'COMPLETED'")
                .with("checked_in_at", "'2026-10-27 08:55+02'")),
        check(
            "appointments_completed_at_consistent",
            "a completion time on a checked-in appointment",
            appointment("APT-AAAA-AAAA")
                .with("status", "'CHECKED_IN'")
                .with("checked_in_at", "'2026-10-27 08:55+02'")
                .with("completed_at", "'2026-10-27 09:15+02'")),
        check(
            "appointments_no_show_at_consistent",
            "NO_SHOW without a time",
            appointment("APT-AAAA-AAAA").with("status", "'NO_SHOW'")),
        check(
            "appointments_no_show_at_consistent",
            "a no-show time on a confirmed appointment",
            appointment("APT-AAAA-AAAA").with("no_show_at", "'2026-10-27 09:15+02'")),
        new Violation(
            "appointments_seat_no_overlap",
            EXCLUSION,
            "one seat, overlapping times",
            appointment("APT-AAAA-AAAA").insert(),
            appointment("APT-BBBB-BBBB").with("customer_mobile", SECOND_MOBILE).insert()),
        new Violation(
            "appointments_customer_no_overlap",
            EXCLUSION,
            "one mobile at two branches at once",
            appointment("APT-AAAA-AAAA").insert(),
            appointment("APT-BBBB-BBBB").with("branch_id", literal(SeedData.BELLVILLE)).insert()),
        // idempotency_keys
        new Violation(
            "idempotency_keys_pkey",
            UNIQUE,
            "the same key twice",
            idempotencyKey().with("key", TEST_ID).insert(),
            idempotencyKey().with("key", TEST_ID).insert()),
        new Violation(
            "idempotency_keys_appointment_fkey",
            FOREIGN_KEY,
            "an unknown appointment",
            idempotencyKey()
                .with("appointment_id", UNKNOWN_ID)
                .with("response_status", "201")
                .insert()),
        check(
            "idempotency_keys_request_hash_format",
            "not a SHA-256 hash",
            idempotencyKey().with("request_hash", "'not-a-hash'")),
        new Violation(
            "idempotency_keys_response_status_check",
            CHECK,
            "a status other than 201",
            booked,
            idempotencyKey()
                .with("appointment_id", APPOINTMENT_ID)
                .with("response_status", "200")
                .insert()),
        new Violation(
            "idempotency_keys_completed_consistent",
            CHECK,
            "an appointment without a status",
            booked,
            idempotencyKey().with("appointment_id", APPOINTMENT_ID).insert()),
        check(
            "idempotency_keys_expiry_after_creation",
            "expires as it is created",
            idempotencyKey().with("expires_at", "'2026-10-26 08:00Z'")),
        // notification_messages
        new Violation(
            "notification_messages_pkey",
            UNIQUE,
            "the same id twice",
            booked,
            message().with("id", TEST_ID).insert(),
            message().with("id", TEST_ID).insert()),
        new Violation(
            "notification_messages_appointment_fkey",
            FOREIGN_KEY,
            "an unknown appointment",
            message().with("appointment_id", UNKNOWN_ID).insert()),
        messageCheck(
            "notification_messages_kind_check",
            "an unknown kind",
            message().with("kind", "'REMINDER'")),
        messageCheck(
            "notification_messages_channel_check",
            "an unknown channel",
            message().with("channel", "'WHATSAPP'")),
        messageCheck(
            "notification_messages_subject_email_only",
            "an SMS with a subject",
            message().with("subject", "'Booked'")),
        messageCheck(
            "notification_messages_subject_email_only",
            "an e-mail without a subject",
            message().with("channel", "'EMAIL'").with("recipient", "'thandi@example.com'")),
        messageCheck(
            "notification_messages_sms_len",
            "an SMS over 320 characters",
            message().with("body", "repeat('x', 321)")),
        messageCheck(
            "notification_messages_status_check",
            "an unknown status",
            message().with("status", "'QUEUED'")),
        messageCheck(
            "notification_messages_attempts_check",
            "a sixth attempt",
            message().with("attempts", "6")),
        messageCheck(
            "notification_messages_failure_reason_check",
            "an unknown reason",
            message().with("status", "'FAILED'").with("failure_reason", "'LOST'")),
        messageCheck(
            "notification_messages_failure_reason_consistent",
            "FAILED without a reason",
            message().with("status", "'FAILED'")),
        messageCheck(
            "notification_messages_failure_reason_consistent",
            "a reason on a pending message",
            message().with("failure_reason", "'DELIVERY_FAILED'")),
        messageCheck(
            "notification_messages_sent_at_consistent",
            "SENT without a time",
            message().with("status", "'SENT'")),
        messageCheck(
            "notification_messages_sent_at_consistent",
            "a sent time on a pending message",
            message().with("sent_at", "'2026-10-26 08:01Z'")),
        check(
            "notification_messages_detached_cancellation_only",
            "a confirmation with no appointment",
            message().with("appointment_id", "NULL")),
        new Violation(
            "notification_messages_detached_cancellation_only",
            CHECK,
            "deleting an appointment that still has its confirmation",
            bookedAppointment().insert(),
            message().insert(),
            "DELETE FROM appointments WHERE id = " + APPOINTMENT_ID));
  }

  static Stream<Acceptance> acceptances() {
    return Stream.of(
        new Acceptance(
            "back-to-back appointments share a seat",
            appointment("APT-AAAA-AAAA").insert(),
            appointment("APT-BBBB-BBBB")
                .with("customer_mobile", SECOND_MOBILE)
                .with("starts_at", "'2026-10-27 09:15+02'")
                .with("ends_at", "'2026-10-27 09:30+02'")
                .insert()),
        new Acceptance(
            "two seats are booked at the same time",
            appointment("APT-AAAA-AAAA").insert(),
            appointment("APT-BBBB-BBBB")
                .with("customer_mobile", SECOND_MOBILE)
                .with("seat_no", "2")
                .insert()),
        new Acceptance(
            "seat 1 of another service is another seat",
            appointment("APT-AAAA-AAAA").insert(),
            appointment("APT-BBBB-BBBB")
                .with("customer_mobile", SECOND_MOBILE)
                .with("service_id", literal(SeedData.CARD))
                .insert()),
        new Acceptance(
            "a cancelled appointment frees its seat",
            appointment("APT-AAAA-AAAA")
                .with("status", "'CANCELLED'")
                .with("cancelled_at", "'2026-10-26 10:00+02'")
                .insert(),
            appointment("APT-BBBB-BBBB").with("customer_mobile", SECOND_MOBILE).insert()),
        new Acceptance(
            "a completed key outlives the appointment it booked",
            idempotencyKey().with("response_status", "201").insert()),
        new Acceptance(
            "a cancellation outlives its appointment",
            message().with("appointment_id", "NULL").with("kind", "'CANCELLATION'").insert()),
        new Acceptance(
            "a completed appointment frees its seat",
            appointment("APT-AAAA-AAAA")
                .with("status", "'COMPLETED'")
                .with("checked_in_at", "'2026-10-27 08:55+02'")
                .with("completed_at", "'2026-10-27 09:10+02'")
                .insert(),
            appointment("APT-BBBB-BBBB").with("customer_mobile", SECOND_MOBILE).insert()),
        new Acceptance(
            "one customer books back to back at two branches",
            appointment("APT-AAAA-AAAA").insert(),
            appointment("APT-BBBB-BBBB")
                .with("branch_id", literal(SeedData.BELLVILLE))
                .with("starts_at", "'2026-10-27 09:15+02'")
                .with("ends_at", "'2026-10-27 09:30+02'")
                .insert()),
        new Acceptance(
            "a cancelled appointment no longer blocks its customer",
            appointment("APT-AAAA-AAAA")
                .with("status", "'CANCELLED'")
                .with("cancelled_at", "'2026-10-26 10:00+02'")
                .insert(),
            appointment("APT-BBBB-BBBB").with("branch_id", literal(SeedData.BELLVILLE)).insert()),
        new Acceptance(
            "names with an inner space, an apostrophe, a hyphen, an accent or one letter",
            appointment("APT-AAAA-AAAA")
                .with("customer_first_name", "'Mary Ann'")
                .with("customer_last_name", "'O''Neil'")
                .insert(),
            appointment("APT-BBBB-BBBB")
                .with("customer_mobile", SECOND_MOBILE)
                .with("customer_first_name", "'Zoë'")
                .with("customer_last_name", "'Van der Merwe-Nkosi'")
                .with("seat_no", "2")
                .insert(),
            appointment("APT-CCCC-CCCC")
                .with("customer_mobile", "'+27825550103'")
                .with("customer_first_name", "'T'")
                .with("seat_no", "3")
                .insert()),
        new Acceptance(
            "references using every one of the 31 symbols",
            appointment("APT-ABCD-EFGH")
                .with("starts_at", "'2026-10-28 09:00+02'")
                .with("ends_at", "'2026-10-28 09:15+02'")
                .insert(),
            appointment("APT-JKMN-PQRS")
                .with("starts_at", "'2026-10-28 10:00+02'")
                .with("ends_at", "'2026-10-28 10:15+02'")
                .insert(),
            appointment("APT-TUVW-XYZ2")
                .with("starts_at", "'2026-10-28 11:00+02'")
                .with("ends_at", "'2026-10-28 11:15+02'")
                .insert(),
            appointment("APT-3456-789A")
                .with("starts_at", "'2026-10-28 12:00+02'")
                .with("ends_at", "'2026-10-28 12:15+02'")
                .insert()));
  }

  // --- Running statements --------------------------------------------------------------------

  /**
   * Runs the statements in one transaction, rolled back afterwards, and returns how the last one
   * was rejected. A failing setup statement fails the test instead of counting as the rejection.
   */
  private PSQLException rejectionOf(List<String> statements) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        for (String setup : statements.subList(0, statements.size() - 1)) {
          statement.execute(setup);
        }
        try {
          statement.execute(statements.getLast());
        } catch (PSQLException rejected) {
          return rejected;
        }
      } finally {
        connection.rollback();
      }
    }
    return fail("The database accepted: " + statements.getLast());
  }

  private void runAndRollBack(List<String> statements) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        for (String sql : statements) {
          statement.execute(sql);
        }
      } finally {
        connection.rollback();
      }
    }
  }

  /** Each row of a two-column query as "first second", with SQL NULL as "null". */
  private static List<String> rows(Statement statement, String sql) throws SQLException {
    List<String> rows = new ArrayList<>();
    try (ResultSet result = statement.executeQuery(sql)) {
      while (result.next()) {
        rows.add(result.getString(1) + " " + result.getString(2));
      }
    }
    return rows;
  }

  private static Set<String> statusesIn(String predicate) {
    Matcher quoted = Pattern.compile("'([A-Z_]+)'").matcher(predicate);
    return quoted.results().map(match -> match.group(1)).collect(Collectors.toSet());
  }

  // --- Cases and rows ------------------------------------------------------------------------

  /**
   * A constraint, the SQLSTATE its violation raises, what the case breaks, and the statements: any
   * setup, then the offending statement last.
   */
  record Violation(String constraint, String sqlState, String example, List<String> statements) {

    Violation(String constraint, String sqlState, String example, String... statements) {
      this(constraint, sqlState, example, List.of(statements));
    }

    @Override
    public String toString() {
      return constraint + ": " + example;
    }
  }

  /** Statements that must all succeed together. */
  record Acceptance(String description, List<String> statements) {

    Acceptance(String description, String... statements) {
      this(description, List.of(statements));
    }

    @Override
    public String toString() {
      return description;
    }
  }

  private static Violation check(String constraint, String example, Row row) {
    return new Violation(constraint, CHECK, example, row.insert());
  }

  private static Violation foreignKey(String constraint, String example, Row row) {
    return new Violation(constraint, FOREIGN_KEY, example, row.insert());
  }

  /** A message needs its appointment to exist first. */
  private static Violation messageCheck(String constraint, String example, Row row) {
    return new Violation(constraint, CHECK, example, bookedAppointment().insert(), row.insert());
  }

  /** One table row as SQL literals: valid as created, until a test changes one column. */
  record Row(String table, Map<String, String> values) {

    static Row of(String table) {
      return new Row(table, Map.of());
    }

    /** Adds a column; used to build the valid rows below. */
    Row column(String column, String sqlLiteral) {
      Map<String, String> extended = new LinkedHashMap<>(values);
      extended.put(column, sqlLiteral);
      return new Row(table, extended);
    }

    /** Changes a column the row already has; used by the cases to break one rule. */
    Row with(String column, String sqlLiteral) {
      if (!values.containsKey(column)) {
        throw new IllegalArgumentException(table + " has no column " + column + " in this row");
      }
      Map<String, String> changed = new LinkedHashMap<>(values);
      changed.put(column, sqlLiteral);
      return new Row(table, changed);
    }

    String insert() {
      return "INSERT INTO %s (%s) VALUES (%s)"
          .formatted(table, String.join(", ", values.keySet()), String.join(", ", values.values()));
    }
  }

  private static String literal(UUID id) {
    return "'" + id + "'";
  }

  private static Row branch() {
    return Row.of("branches")
        .column("id", "gen_random_uuid()")
        .column("code", "'TST'")
        .column("name", "'Test Branch'")
        .column("sms_name", "'Test Branch'")
        .column("address_line1", "'1 Test Road'")
        .column("suburb", "'Testville'")
        .column("city", "'Test City'")
        .column("province", "'Gauteng'")
        .column("postal_code", "'2000'");
  }

  /** Sunday hours for Rustenburg, which has none. */
  private static Row hours() {
    return Row.of("branch_operating_hours")
        .column("branch_id", literal(SeedData.RUSTENBURG))
        .column("weekday", "7")
        .column("opens_at", "'09:00'")
        .column("closes_at", "'13:00'");
  }

  private static Row service() {
    return Row.of("services")
        .column("id", "gen_random_uuid()")
        .column("code", "'TEST'")
        .column("name", "'Test service'")
        .column("sms_name", "'Test service'")
        .column("description", "'A service that only tests use.'")
        .column("duration_minutes", "30")
        .column("documents_needed", "ARRAY['Valid SA ID']")
        .column("sort_order", "99");
  }

  /** Home loans at Gqeberha, which does not offer them. */
  private static Row branchService() {
    return Row.of("branch_services")
        .column("branch_id", literal(SeedData.GQEBERHA))
        .column("service_id", literal(SeedData.HOME_LOAN))
        .column("capacity", "2");
  }

  private static Row holiday() {
    return Row.of("public_holidays")
        .column("holiday_date", "'2030-01-01'")
        .column("name", "'New Year''s Day'")
        .column("source", "'SCHEDULE_1'");
  }

  /** A FICA appointment at Sandton, seat 1, 09:00 to 09:15 on Tuesday 27 October 2026. */
  /**
   * Returns the regular expression a CHECK constraint applies to one column, as PostgreSQL stores
   * it: the literal in {@code ((column)::text ~ '...'::text)}.
   */
  private String regexIn(String constraint, String column) {
    String definition =
        jdbc.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
            String.class,
            constraint);
    Matcher regex =
        Pattern.compile("\\(" + column + "\\)::text ~ '(.*?)'::text").matcher(definition);
    assertThat(regex.find()).as("a regular expression on %s in %s", column, definition).isTrue();
    return regex.group(1);
  }

  private static Row appointment(String reference) {
    return Row.of("appointments")
        .column("id", "gen_random_uuid()")
        .column("reference", "'" + reference + "'")
        .column("branch_id", literal(SeedData.SANDTON))
        .column("service_id", literal(SeedData.FICA))
        .column("seat_no", "1")
        .column("starts_at", "'2026-10-27 09:00+02'")
        .column("ends_at", "'2026-10-27 09:15+02'")
        .column("status", "'CONFIRMED'")
        .column("customer_first_name", "'Thandi'")
        .column("customer_last_name", "'Mokoena'")
        .column("customer_mobile", FIRST_MOBILE)
        .column("cancelled_at", "NULL")
        .column("checked_in_at", "NULL")
        .column("completed_at", "NULL")
        .column("no_show_at", "NULL");
  }

  /** An appointment with a known id, for rows that refer to it. */
  private static Row bookedAppointment() {
    return appointment("APT-CCCC-CCCC").with("id", APPOINTMENT_ID);
  }

  private static Row idempotencyKey() {
    return Row.of("idempotency_keys")
        .column("key", "gen_random_uuid()")
        .column("request_hash", "repeat('a', 64)")
        .column("appointment_id", "NULL")
        .column("response_status", "NULL")
        .column("created_at", "'2026-10-26 08:00Z'")
        .column("expires_at", "'2026-10-27 08:00Z'");
  }

  /** A pending confirmation SMS for {@link #bookedAppointment()}. */
  private static Row message() {
    return Row.of("notification_messages")
        .column("id", "gen_random_uuid()")
        .column("appointment_id", APPOINTMENT_ID)
        .column("kind", "'CONFIRMATION'")
        .column("channel", "'SMS'")
        .column("recipient", FIRST_MOBILE)
        .column("subject", "NULL")
        .column("body", "'Your appointment is confirmed.'")
        .column("status", "'PENDING'")
        .column("attempts", "0")
        .column("failure_reason", "NULL")
        .column("correlation_id", "'test'")
        .column("sent_at", "NULL");
  }
}
