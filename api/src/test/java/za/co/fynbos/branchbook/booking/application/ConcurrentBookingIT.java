package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;

/**
 * BR-7, BR-22, NFR-2: bookings that arrive at the same moment, released together by a latch from
 * twenty threads through the whole application. The seat lock decides who wins, so the outcome is
 * exact, and no racer waits long enough to be told to try again. A warm-up booking first takes the
 * first-use costs out of the race. The mobiles are distinct demo numbers, so only the rule under
 * test can refuse a racer.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class ConcurrentBookingIT {

  private static final int RACERS = 20;

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void warmUp() {
    assertThat(book(mvc, body("UMH", "CARD", "2026-10-29T09:00:00+02:00", "082 555 0119")))
        .hasStatus(HttpStatus.CREATED);
  }

  /** Polokwane has one FICA seat: exactly one racer books it, the rest are told it is full. */
  @Test
  void twentyRacersForOneSeatProduceExactlyOne201() throws Exception {
    List<MvcTestResult> results =
        race(racer -> body("POL", "FICA", "2026-10-27T09:00:00+02:00", mobile(racer)));

    assertThat(outcomes(results)).isEqualTo(Map.of("201", 1L, "409 SLOT_FULL", 19L));
    assertThat(rowsAt("POL", "2026-10-27 07:00:00+00")).isOne();
    assertThat(messagesAt("POL", "2026-10-27 07:00:00+00")).isOne();
  }

  /** Sandton has three FICA seats: exactly three racers book them, on three different seats. */
  @Test
  void twentyRacersForThreeSeatsProduceExactlyThree201s() throws Exception {
    List<MvcTestResult> results =
        race(racer -> body("SAN", "FICA", "2026-10-27T09:00:00+02:00", mobile(racer)));

    assertThat(outcomes(results)).isEqualTo(Map.of("201", 3L, "409 SLOT_FULL", 17L));
    assertThat(
            jdbc.queryForList(
                "SELECT seat_no FROM appointments a JOIN branches b ON b.id = a.branch_id"
                    + " WHERE b.code = 'SAN' ORDER BY seat_no",
                Integer.class))
        .containsExactly(1, 2, 3);
    assertThat(messagesAt("SAN", "2026-10-27 07:00:00+00")).isEqualTo(3);
  }

  /**
   * One customer books two overlapping times at two branches at the same moment, ten times over:
   * the per-mobile lock queues the pair, so one is booked and the other is told it overlaps, never
   * both and never neither.
   */
  @Test
  void sameMobileTwoBranchesOverlappingConcurrentlyGivesOne201AndOne409CustomerOverlap()
      throws Exception {
    for (int round = 0; round < 10; round++) {
      String mobile = "082 555 01" + (40 + round);
      // A slot of its own per round, 10:00 to 14:30, so no round meets another's capacity.
      LocalTime time = LocalTime.of(10, 0).plusMinutes(30L * round);
      String startsAt = "2026-10-27T" + time + ":00+02:00";
      List<Map<String, Object>> pair =
          List.of(body("SAN", "FICA", startsAt, mobile), body("BEL", "CARD", startsAt, mobile));

      List<MvcTestResult> results = race(pair.size(), pair::get);

      assertThat(outcomes(results))
          .as("round %d", round)
          .isEqualTo(Map.of("201", 1L, "409 CUSTOMER_OVERLAP", 1L));
    }
  }

  private List<MvcTestResult> race(Function<Integer, Map<String, Object>> bodies) throws Exception {
    return race(RACERS, bodies);
  }

  /** Releases the bookings together and waits for all of them. */
  private List<MvcTestResult> race(int racers, Function<Integer, Map<String, Object>> bodies)
      throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Future<MvcTestResult>> pending = new ArrayList<>();
    try (ExecutorService threads = Executors.newFixedThreadPool(racers)) {
      for (int racer = 0; racer < racers; racer++) {
        Map<String, Object> request = bodies.apply(racer);
        pending.add(
            threads.submit(
                () -> {
                  start.await();
                  return book(mvc, request);
                }));
      }
      start.countDown();
    }
    List<MvcTestResult> results = new ArrayList<>();
    for (Future<MvcTestResult> result : pending) {
      results.add(result.get());
    }
    return results;
  }

  /** Counts the outcomes: "201", or the status and code of each refusal. */
  private static Map<String, Long> outcomes(List<MvcTestResult> results) {
    return results.stream()
        .map(
            result -> {
              int status = result.getResponse().getStatus();
              return status == 201 ? "201" : status + " " + code(result);
            })
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
  }

  private static String code(MvcTestResult result) {
    return JsonPath.read(
        new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.code");
  }

  /** Synthetic demo numbers, one per racer: 082 555 0120 to 082 555 0139. */
  private static String mobile(int racer) {
    return "082 555 01" + (20 + racer);
  }

  private int rowsAt(String branch, String startsAt) {
    return count(
        "SELECT count(*) FROM appointments a JOIN branches b ON b.id = a.branch_id"
            + " WHERE b.code = ? AND a.starts_at = ?::timestamptz",
        branch,
        startsAt);
  }

  private int messagesAt(String branch, String startsAt) {
    return count(
        "SELECT count(*) FROM notification_messages m JOIN appointments a ON a.id = m.appointment_id"
            + " JOIN branches b ON b.id = a.branch_id WHERE b.code = ? AND a.starts_at = ?::timestamptz",
        branch,
        startsAt);
  }

  private int count(String sql, Object... args) {
    Integer rows = jdbc.queryForObject(sql, Integer.class, args);
    return rows == null ? 0 : rows;
  }
}
