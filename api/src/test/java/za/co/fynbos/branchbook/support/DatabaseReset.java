package za.co.fynbos.branchbook.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import za.co.fynbos.branchbook.booking.application.LookupAttemptLimiter;

/**
 * Empties the booking tables before and after each test of a class that books through the API, so
 * no test sees another's appointments, and no leftover row reaches a test class that does not use
 * this extension. It also forgets scripted references and lookup counts. The reference data is
 * never touched.
 */
public class DatabaseReset implements BeforeEachCallback, AfterEachCallback {

  @Override
  public void beforeEach(ExtensionContext context) {
    reset(context);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    reset(context);
  }

  private static void reset(ExtensionContext context) {
    ApplicationContext application = SpringExtension.getApplicationContext(context);
    application
        .getBean(JdbcTemplate.class)
        .execute("TRUNCATE notification_messages, idempotency_keys, appointments");
    application.getBean(ScriptedReferenceGenerator.class).clear();
    application.getBean(LookupAttemptLimiter.class).reset();
  }
}
