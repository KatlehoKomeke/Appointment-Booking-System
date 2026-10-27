package za.co.fynbos.branchbook.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Marks an integration test: the whole application, on a real PostgreSQL 17 in Docker, with a
 * {@link MutableClock} and MockMvc.
 *
 * <p>Integration tests that use only this configuration share one context, so Spring builds it once
 * and one database container serves them all. A class that switches a setting with
 * {@code @TestPropertySource}, as {@code InboxSwitchedOffIT}, {@code InboxReferencesHiddenIT},
 * {@code DispatcherLoopIT}, {@code DemoNumbersOnlyIT} and {@code RecipientDailyCapIT} do, builds a
 * context of its own, and with it a container of its own, because the container is a bean of the
 * context; each one adds several seconds to the run. Before every test the clock is back at {@link
 * TestClockConfiguration#DEFAULT_INSTANT}, so no test depends on the order tests run in.
 *
 * <p>The simulated inbox is switched on, as it is in docker compose, and the dispatcher's own loop
 * is off with no gateway latency: tests that deliver call {@code NotificationDispatcher.runOnce}
 * with the clock's instant, so no test sleeps. Bookings take any South African mobile, the inbox
 * shows references, every SMS goes through the simulated gateway and no daily cap applies, as
 * everywhere but the hosted demo; all are set here rather than left to the default, so a {@code
 * BRANCHBOOK_BOOKING_DEMO_NUMBERS_ONLY}, {@code BRANCHBOOK_INBOX_REDACT_REFERENCES}, {@code
 * BRANCHBOOK_SMS_GATEWAY} or {@code BRANCHBOOK_SMS_RECIPIENT_DAILY_CAP} exported in the developer's
 * shell cannot change what the tests prove, nor make a test send a real SMS. References come from a
 * {@link ScriptedReferenceGenerator}, which hands out random ones unless a test scripts them.
 * Classes that book through the API add {@link DatabaseReset}.
 *
 * <p>Metrics are exported as the application exports them, so {@code /actuator/prometheus} answers:
 * Spring Boot switches export off in tests unless {@link AutoConfigureMetrics} asks for it. Every
 * test shares the meters, so a test reads how a counter moved, never its absolute value.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
    properties = {
      "branchbook.inbox.enabled=true",
      "branchbook.inbox.redact-references=false",
      "branchbook.dispatcher.enabled=false",
      "branchbook.dispatcher.simulated-latency=PT0S",
      "branchbook.booking.demo-numbers-only=false",
      "branchbook.sms.gateway=simulated",
      "branchbook.sms.recipient-daily-cap=0"
    })
@ExtendWith(ResetClockExtension.class)
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import({
  TestcontainersConfiguration.class,
  TestClockConfiguration.class,
  TestReferenceConfiguration.class
})
public @interface IntegrationTest {}
