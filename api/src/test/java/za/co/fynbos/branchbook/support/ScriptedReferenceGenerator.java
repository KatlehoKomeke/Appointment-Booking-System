package za.co.fynbos.branchbook.support;

import java.security.SecureRandom;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import za.co.fynbos.branchbook.booking.application.ReferenceGenerator;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;

/**
 * Hands out the references a test scripted, then random ones, so a test can force a reference
 * collision. Replaces the application's generator in every integration test.
 */
public class ScriptedReferenceGenerator implements ReferenceGenerator {

  private final Deque<AppointmentReference> script = new ConcurrentLinkedDeque<>();
  private final SecureRandom random = new SecureRandom();

  /**
   * Queues references to hand out, in order, before random ones.
   *
   * @param references references in their stored form
   */
  public void script(String... references) {
    for (String reference : references) {
      script.add(new AppointmentReference(reference));
    }
  }

  /** Forgets the scripted references that were not used. */
  public void clear() {
    script.clear();
  }

  @Override
  public AppointmentReference next() {
    AppointmentReference scripted = script.poll();
    return scripted != null ? scripted : AppointmentReference.generate(random);
  }
}
