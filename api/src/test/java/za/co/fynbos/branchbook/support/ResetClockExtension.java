package za.co.fynbos.branchbook.support;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Puts the shared {@link MutableClock} back to {@link TestClockConfiguration#DEFAULT_INSTANT}
 * before every integration test, so no test sees the time another test set, whatever order they run
 * in. A test's own {@code @BeforeEach} runs after this and may set another instant.
 */
public class ResetClockExtension implements BeforeEachCallback {

  @Override
  public void beforeEach(ExtensionContext context) {
    SpringExtension.getApplicationContext(context)
        .getBean(MutableClock.class)
        .setInstant(TestClockConfiguration.DEFAULT_INSTANT);
  }
}
