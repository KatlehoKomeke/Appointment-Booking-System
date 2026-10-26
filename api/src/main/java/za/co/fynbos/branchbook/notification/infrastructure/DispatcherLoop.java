package za.co.fynbos.branchbook.notification.infrastructure;

import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import za.co.fynbos.branchbook.notification.application.DispatcherProperties;
import za.co.fynbos.branchbook.notification.application.NotificationDispatcher;

/**
 * Runs the dispatcher every {@code interval} on a single thread of its own, {@code outbox-1}
 * (BR-17).
 *
 * <p>The thread is private to this loop, not a scheduler bean: a {@code TaskScheduler} bean would
 * replace Spring Boot's default scheduler, and the hourly key purge and the daily holiday check
 * would run on the dispatcher's thread. The isolation is the point, not throughput. On shutdown the
 * tick in flight is allowed to finish. The loop exists only while {@code
 * branchbook.dispatcher.enabled} is true; the tests switch it off and call {@link
 * NotificationDispatcher#runOnce} themselves.
 */
@Component
@ConditionalOnProperty(name = "branchbook.dispatcher.enabled", havingValue = "true")
class DispatcherLoop implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(DispatcherLoop.class);

  private final NotificationDispatcher dispatcher;
  private final DispatcherProperties properties;
  private final Clock clock;
  private @Nullable ThreadPoolTaskScheduler scheduler;

  DispatcherLoop(NotificationDispatcher dispatcher, DispatcherProperties properties, Clock clock) {
    this.dispatcher = dispatcher;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public synchronized void start() {
    ThreadPoolTaskScheduler outbox = new ThreadPoolTaskScheduler();
    outbox.setPoolSize(1);
    outbox.setThreadNamePrefix("outbox-");
    outbox.setWaitForTasksToCompleteOnShutdown(true);
    outbox.setAwaitTerminationSeconds(10);
    outbox.initialize();
    outbox.scheduleWithFixedDelay(this::tick, properties.interval());
    scheduler = outbox;
  }

  @Override
  public synchronized void stop() {
    if (scheduler != null) {
      scheduler.shutdown();
      scheduler = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return scheduler != null;
  }

  /** One tick; a failure is logged by type and the next tick runs as usual. */
  private void tick() {
    try {
      dispatcher.runOnce(clock.instant());
    } catch (RuntimeException failure) {
      log.error("outbox.tick_failed cause={}", failure.getClass().getSimpleName());
    }
  }
}
