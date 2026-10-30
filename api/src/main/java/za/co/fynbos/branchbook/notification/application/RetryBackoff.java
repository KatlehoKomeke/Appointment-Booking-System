package za.co.fynbos.branchbook.notification.application;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * How long a message waits after a retryable refusal before the dispatcher tries it again (BR-17),
 * from {@code branchbook.dispatcher.backoff}.
 *
 * <p>The nominal wait after attempt {@code n} is {@code base × multiplier^(n−1)}, at most {@code
 * cap}: with the defaults 2, 4, 8 and 16 seconds, as before. A random share of up to {@code jitter}
 * of it is then taken off, so with the default 0.2 the first wait falls between 1.6 and 2 seconds.
 * Messages refused together, as during a provider's outage, are then not all due again at the same
 * instant. Jitter never lengthens a wait, so the cap is a true ceiling and a message still reaches
 * FAILED within the 30 seconds it did before.
 */
@Component
class RetryBackoff {

  private final DispatcherProperties.Backoff settings;
  private final DoubleSupplier random;

  /**
   * The application's backoff, with a fresh random share for every wait.
   *
   * @param dispatcher the dispatcher's settings, whose {@code backoff} this reads
   */
  @Autowired
  RetryBackoff(DispatcherProperties dispatcher) {
    this(dispatcher.backoff(), () -> ThreadLocalRandom.current().nextDouble());
  }

  /**
   * A backoff whose random share the caller supplies; the tests pass fixed ones.
   *
   * @param settings base, multiplier, cap and jitter
   * @param random answers a number from 0 inclusive to 1 exclusive for every wait
   */
  RetryBackoff(DispatcherProperties.Backoff settings, DoubleSupplier random) {
    this.settings = settings;
    this.random = random;
  }

  /**
   * The wait before the next attempt, with jitter.
   *
   * @param attempts the attempts made so far, at least 1
   * @return a wait from {@code nominal × (1 − jitter)} up to {@code nominal}, never zero
   */
  Duration after(int attempts) {
    Duration nominal = nominalAfter(attempts);
    double share = Math.clamp(random.getAsDouble(), 0.0, 1.0) * settings.jitter();
    long millis = Math.round(nominal.toMillis() * (1.0 - share));
    return Duration.ofMillis(Math.max(1, millis));
  }

  /**
   * The wait before the next attempt without jitter: the top of the band {@link #after} draws from.
   *
   * @param attempts the attempts made so far, at least 1
   * @return {@code base × multiplier^(attempts−1)}, at most {@code cap}
   */
  Duration nominalAfter(int attempts) {
    double factor = Math.pow(settings.multiplier(), Math.max(0, attempts - 1));
    double millis = settings.base().toMillis() * factor;
    long cap = settings.cap().toMillis();
    return Duration.ofMillis(millis >= cap ? cap : Math.round(millis));
  }
}
