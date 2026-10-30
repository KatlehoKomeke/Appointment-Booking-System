package za.co.fynbos.branchbook.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * ADR-0006: the published demo block is configuration ({@code
 * branchbook.booking.demo-number-block}), so a mistyped block must stop start-up rather than
 * quietly let real numbers through.
 */
class DemoNumberBlockTest {

  @Test
  void aConfiguredBlockIncludesBothEnds() {
    DemoNumberBlock block = DemoNumberBlock.of("+27825550000", "+27825550009");

    assertThat(block.contains(MobileNumber.ofE164("+27825550000"))).isTrue();
    assertThat(block.contains(MobileNumber.ofE164("+27825550009"))).isTrue();
    assertThat(block.contains(MobileNumber.ofE164("+27825550010"))).isFalse();
  }

  @Test
  void aBlockWhoseFirstNumberComesAfterItsLastIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DemoNumberBlock.of("+27825550199", "+27825550000"));
  }

  @Test
  void aBlockEndThatIsNotASouthAfricanMobileIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DemoNumberBlock.of("0825550000", "+27825550199"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DemoNumberBlock.of("+27825550000", "+27215550199"));
  }

  /** The block is public, so the start-up log may name it the way the README prints it. */
  @Test
  void itNamesItselfAsTheReadmePrintsIt() {
    assertThat(DemoNumberBlock.of("+27825550000", "+27825550199"))
        .hasToString("082 555 0000 to 082 555 0199");
  }
}
