package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;
import static za.co.fynbos.branchbook.support.Bookings.cancel;
import static za.co.fynbos.branchbook.support.Bookings.customer;
import static za.co.fynbos.branchbook.support.Bookings.lookup;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.notification.application.NotificationDispatcher;
import za.co.fynbos.branchbook.shared.correlation.CorrelationIds;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;

/**
 * BR-19 under structured logs: with the Elastic Common Schema JSON lines that {@code
 * logging.structured.format.console=ecs} switches on ({@code LOGGING_STRUCTURED_FORMAT_CONSOLE}), a
 * booking, its lookups, its confirmation's delivery and its cancellation leave every line valid
 * JSON, carry the request's correlation id as a field of its own, and hold neither the customer's
 * name or number nor the appointment's reference, in any field.
 *
 * <p>Spring Boot sets up logging once per JVM, so a property given to this one test context would
 * not change the format once another context has started. The test therefore attaches the very
 * encoder that property configures, Spring Boot's {@link StructuredLogEncoder} with the format
 * {@code ecs}, to the root logger for the length of each test, and reads what it writes.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class StructuredLogRedactionIT {

  /** The customer's details, synthetic and unlike anything else the application logs. */
  private static final String FIRST_NAME = "Thandeka";

  private static final String LAST_NAME = "Mabaso";

  /** From the demo range in SECURITY.md, typed as a customer would. */
  private static final String MOBILE = "082 555 0168";

  private static final String ANY_FORM_OF_THE_NUMBER = "(?s).*555\\D{0,3}0168.*";

  private static final String APPENDER = "structured-log-redaction";

  @Autowired private MockMvcTester mvc;

  @Autowired private MutableClock clock;

  @Autowired private NotificationDispatcher dispatcher;

  private final ByteArrayOutputStream written = new ByteArrayOutputStream();

  @BeforeEach
  void writeEcsLinesBesideTheConsole() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    StructuredLogEncoder encoder = new StructuredLogEncoder();
    encoder.setContext(context);
    encoder.setFormat("ecs");
    encoder.setCharset(StandardCharsets.UTF_8);
    encoder.start();
    OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
    appender.setName(APPENDER);
    appender.setContext(context);
    appender.setEncoder(encoder);
    appender.setOutputStream(written);
    appender.start();
    context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
  }

  @AfterEach
  void detach() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
    root.getAppender(APPENDER).stop();
    root.detachAppender(APPENDER);
  }

  @Test
  void ecsLinesCarryTheCorrelationIdAndNothingOfTheCustomer() {
    Map<String, Object> ours = body("SAN", "FICA", "2026-10-27T09:30:00+02:00", MOBILE);
    customer(ours).put("firstName", FIRST_NAME);
    customer(ours).put("lastName", LAST_NAME);
    MvcTestResult booked = book(mvc, ours);
    assertThat(booked).hasStatus(HttpStatus.CREATED);
    String reference = JsonPath.read(text(booked), "$.reference");
    String correlationId = booked.getResponse().getHeader(CorrelationIds.HEADER);

    assertThat(lookup(mvc, reference, "0168")).hasStatus(HttpStatus.OK);
    assertThat(lookup(mvc, reference, "1234")).hasStatus(HttpStatus.NOT_FOUND);
    assertThat(dispatcher.runOnce(clock.instant())).isOne();
    assertThat(cancel(mvc, reference, "0168")).hasStatus(HttpStatus.OK);
    assertThat(dispatcher.runOnce(clock.instant())).isOne();

    String all = written.toString(StandardCharsets.UTF_8);
    List<String> lines = all.lines().filter(line -> !line.isBlank()).toList();
    assertThat(lines)
        .as("the events were logged, so an empty capture cannot pass")
        .hasSizeGreaterThan(3);
    List<DocumentContext> events = lines.stream().map(JsonPath::parse).toList();
    assertThat(events)
        .as("every line is an ECS event")
        .allSatisfy(
            event -> {
              assertThat(event.read("$['@timestamp']", String.class)).isNotBlank();
              assertThat(event.read("$.log.level", String.class)).isNotBlank();
              assertThat(event.read("$.message", String.class)).isNotBlank();
              assertThat(event.read("$.ecs.version", String.class)).isNotBlank();
            });
    assertThat(events)
        .filteredOn(event -> event.read("$.message", String.class).startsWith("booking.confirmed"))
        .singleElement()
        .satisfies(
            event ->
                assertThat(event.read("$." + CorrelationIds.MDC_KEY, String.class))
                    .as("the request's correlation id is a field of its own")
                    .isEqualTo(correlationId));
    assertThat(all)
        .contains("outbox.sent", "cancel.deleted", "outbox.deleted")
        .doesNotContain(FIRST_NAME)
        .doesNotContain(LAST_NAME)
        .doesNotContain(reference)
        .doesNotContain(reference.replace("-", ""))
        .doesNotMatch(ANY_FORM_OF_THE_NUMBER);
  }

  private static String text(MvcTestResult result) {
    return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
  }
}
