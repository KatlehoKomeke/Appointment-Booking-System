package za.co.fynbos.branchbook.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Empties the booking tables, the daily cap's ledger and the lookup limiter's counts, before and
 * after each test of a class that books through the API, so no test sees another's appointments,
 * counts another's SMS or inherits another's wrong lookups, and no leftover row reaches a test
 * class that does not use this extension. It also forgets scripted references. The reference data
 * is never touched.
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
        .execute(
            "TRUNCATE notification_messages, idempotency_keys, appointments, sms_recipient_sends,"
                + " lookup_attempts");
    application.getBean(ScriptedReferenceGenerator.class).clear();
  }
}
