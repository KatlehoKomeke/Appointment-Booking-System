package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;

import com.jayway.jsonpath.JsonPath;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import za.co.fynbos.branchbook.booking.application.AppointmentDirectory;
import za.co.fynbos.branchbook.booking.application.RetentionPurge;
import za.co.fynbos.branchbook.booking.application.RetentionPurge.Purged;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.MutableClock;

/**
 * The simulated inbox stays whole while the retention purge deletes bookings. It reads the newest
 * messages, then names their appointments in a second statement, so a purge that commits between
 * the two would leave a message whose appointment is gone, and the inbox would answer 500. Both
 * reads must see one snapshot.
 *
 * <p>The race is made certain rather than waited for: the inbox is built around the real
 * repository, wrapped so that the moment its read of the messages returns, the purge runs to its
 * commit on another thread. The inbox gets the same transaction its {@code @Transactional} gives it
 * in the application.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class InboxQueriesIT {

  @Autowired private MockMvcTester mvc;

  @Autowired private MutableClock clock;

  @Autowired private NotificationMessageRepository messages;

  @Autowired private AppointmentDirectory appointments;

  @Autowired private RetentionPurge retention;

  @Autowired private TransactionManager transactionManager;

  @Test
  void aPurgeThatCommitsBetweenTheTwoReadsLeavesTheListWhole() {
    String reference =
        reference(book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0188")));
    Instant fortyEightHoursOn = clock.instant().plus(Duration.ofHours(48));
    AtomicReference<Purged> purged = new AtomicReference<>();
    InboxQueries inbox =
        transactional(
            new InboxQueries(
                purgingOnceMessagesAreRead(() -> purged.set(retention.purge(fortyEightHoursOn))),
                appointments));

    List<InboxMessage> listed = inbox.newest(50);

    assertThat(purged.get())
        .as("the purge committed between the two reads")
        .isEqualTo(new Purged(1, 1, 1));
    assertThat(listed).singleElement().extracting(InboxMessage::reference).isEqualTo(reference);
  }

  /** The real repository, except that each read of the newest messages is followed by the purge. */
  private NotificationMessageRepository purgingOnceMessagesAreRead(Runnable purge) {
    return (NotificationMessageRepository)
        Proxy.newProxyInstance(
            NotificationMessageRepository.class.getClassLoader(),
            new Class<?>[] {NotificationMessageRepository.class},
            (proxy, method, args) -> {
              Object result;
              try {
                result = method.invoke(messages, args);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
              if (method.getName().equals("newest")) {
                CompletableFuture.runAsync(purge).join();
              }
              return result;
            });
  }

  /** The inbox with the transaction Spring would give it, read from its own annotations. */
  private InboxQueries transactional(InboxQueries target) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    return (InboxQueries) factory.getProxy();
  }

  private static String reference(MvcTestResult booked) {
    return JsonPath.read(
        new String(booked.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
        "$.reference");
  }
}
