package za.co.fynbos.branchbook.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/** BR-18: how many septets a text takes in one SMS segment. */
class Gsm7Test {

  @Test
  void basicCharactersCountOneSeptet() {
    assertThat(Gsm7.septets("Ref APT-7K3M-9Q2X. Bring ID + docs.")).isEqualTo(35);
  }

  @Test
  void extensionCharactersCountTwoSeptets() {
    assertThat(Gsm7.septets("[]")).isEqualTo(4);
    assertThat(Gsm7.septets("a|b")).isEqualTo(4);
  }

  /** A curly apostrophe would switch the message to UCS-2 and cut a segment to 70 characters. */
  @Test
  void charactersOutsideTheAlphabetAreNotEncodable() {
    String curlyApostrophe = Character.toString(0x2019);

    assertThat(Gsm7.isEncodable("O'Brien")).isTrue();
    assertThat(Gsm7.isEncodable("O" + curlyApostrophe + "Brien")).isFalse();
    assertThatIllegalArgumentException().isThrownBy(() -> Gsm7.septets("O" + curlyApostrophe));
  }
}
