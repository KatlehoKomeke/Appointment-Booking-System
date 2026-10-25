package za.co.fynbos.branchbook.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fynbos.branchbook.catalogue.domain.ServiceOffer;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.SeedData;

/** The reads booking uses to show an appointment's branch and service, on the seeded catalogue. */
@IntegrationTest
class CatalogueLookupIT {

  @Autowired private CatalogueLookup lookup;

  @Autowired private BranchCalendarProvider calendars;

  @Autowired private JdbcTemplate jdbc;

  @Test
  void findsSeededBranchAndServiceById() {
    assertThat(lookup.branch(SeedData.SANDTON).code()).isEqualTo("SAN");
    assertThat(lookup.service(SeedData.HOME_LOAN).durationMinutes()).isEqualTo(45);
    assertThat(lookup.service(SeedData.FICA).documentsNeeded()).isNotEmpty();
  }

  /** An appointment keeps its branch, even after the branch stops taking bookings. */
  @Test
  void findsABranchThatIsNoLongerActive() {
    try {
      jdbc.update("UPDATE branches SET active = false WHERE id = ?", SeedData.RUSTENBURG);

      assertThat(lookup.branch(SeedData.RUSTENBURG).code()).isEqualTo("RUS");
    } finally {
      jdbc.update("UPDATE branches SET active = true WHERE id = ?", SeedData.RUSTENBURG);
    }
  }

  @Test
  void unknownIdIsADataError() {
    assertThatIllegalStateException().isThrownBy(() -> lookup.branch(UUID.randomUUID()));
  }

  /** An offer carries the service's short name, which the confirmation SMS uses. */
  @Test
  void offerCarriesTheServicesShortName() {
    ServiceOffer offer = calendars.offerFor(lookup.branch(SeedData.SANDTON), "FICA");

    assertThat(offer.smsName()).isEqualTo("FICA update");
  }
}
