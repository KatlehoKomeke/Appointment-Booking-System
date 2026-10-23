package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fynbos.branchbook.catalogue.application.CatalogueReadPort;
import za.co.fynbos.branchbook.catalogue.domain.PublicHolidayCalendar;
import za.co.fynbos.branchbook.support.IntegrationTest;

/**
 * Pins the facts about the seeded reference data that the README, the demos and later tests rely
 * on, so a change to the seed migrations cannot silently break them.
 */
@IntegrationTest
class ReferenceDataIT {

  @Autowired private JdbcTemplate jdbc;

  @Autowired private CatalogueReadPort catalogue;

  @Test
  void sixBranchesInSixProvinces() {
    assertThat(jdbc.queryForObject("SELECT count(*) FROM branches WHERE active", Integer.class))
        .isEqualTo(6);
    assertThat(jdbc.queryForObject("SELECT count(DISTINCT province) FROM branches", Integer.class))
        .isEqualTo(6);
  }

  /** The two-tab race demo needs a branch where one booking fills a slot. */
  @Test
  void polokwaneHasOneSeatForEveryService() {
    List<Integer> capacities =
        jdbc.queryForList(
            """
            SELECT bs.capacity FROM branch_services bs JOIN branches b ON b.id = bs.branch_id
            WHERE b.code = 'POL'""",
            Integer.class);

    assertThat(capacities).hasSize(6).containsOnly(1);
  }

  @Test
  void gqeberhaOffersEveryServiceExceptHomeLoans() {
    List<String> offered =
        jdbc.queryForList(
            """
            SELECT s.code FROM branch_services bs
            JOIN branches b ON b.id = bs.branch_id JOIN services s ON s.id = bs.service_id
            WHERE b.code = 'GQE'""",
            String.class);

    assertThat(offered).containsExactlyInAnyOrder("FICA", "CARD", "OPEN", "CREDIT", "ESTATE");
  }

  @Test
  void onlyTheTwoMallBranchesTradeOnSundays() {
    List<String> sundayBranches =
        jdbc.queryForList(
            """
            SELECT b.code FROM branch_operating_hours h JOIN branches b ON b.id = h.branch_id
            WHERE h.weekday = 7""",
            String.class);

    assertThat(sundayBranches).containsExactlyInAnyOrder("SAN", "UMH");
  }

  /** BR-3, end to end: the seeded holidays plus section 2(1) give the three observed Mondays. */
  @Test
  void seededHolidaysYieldTheObservedMondaysOf2026And2027() {
    PublicHolidayCalendar calendar = new PublicHolidayCalendar(catalogue.publicHolidays());

    assertThat(calendar.holidayOn(LocalDate.of(2026, 8, 10)))
        .contains("National Women's Day (observed)");
    assertThat(calendar.holidayOn(LocalDate.of(2027, 3, 22)))
        .contains("Human Rights Day (observed)");
    assertThat(calendar.holidayOn(LocalDate.of(2027, 12, 27)))
        .contains("Day of Goodwill (observed)");
    assertThat(calendar.holidayOn(LocalDate.of(2026, 11, 4)))
        .contains("Local Government Elections");
    assertThat(calendar.coveredThrough()).isEqualTo(LocalDate.of(2027, 12, 31));
  }
}
