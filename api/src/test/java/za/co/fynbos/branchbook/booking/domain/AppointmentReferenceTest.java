package za.co.fynbos.branchbook.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.security.SecureRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** BR-11: references are unambiguous to read, unguessable, and forgiving to type. */
class AppointmentReferenceTest {

  @Test
  void usesOnlyUnambiguousAlphabet() {
    SecureRandom random = new SecureRandom();

    assertThat(AppointmentReference.ALPHABET.chars().distinct().count()).isEqualTo(31);
    assertThat(AppointmentReference.ALPHABET).doesNotContainAnyWhitespaces();
    for (char misread : "ILO01".toCharArray()) {
      assertThat(AppointmentReference.ALPHABET).doesNotContain(String.valueOf(misread));
    }
    IntStream.range(0, 10_000)
        .mapToObj(i -> AppointmentReference.generate(random).value())
        .forEach(reference -> assertThat(reference).matches(AppointmentReference.PATTERN));
  }

  @Test
  void normalisesLowercaseAndMissingHyphensOnLookup() {
    assertThat(AppointmentReference.parse("apt7k3m9q2x"))
        .map(AppointmentReference::value)
        .contains("APT-7K3M-9Q2X");
    assertThat(AppointmentReference.parse(" APT-7K3M9Q2X "))
        .map(AppointmentReference::value)
        .contains("APT-7K3M-9Q2X");
    assertThat(AppointmentReference.parse("Apt-7k3m-9q2x"))
        .map(AppointmentReference::value)
        .contains("APT-7K3M-9Q2X");
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "7K3M9Q2X",
        "APT-0OIL-1111",
        "APT-7K3M-9Q2",
        "APT-7K3M-9Q2XX",
        "REF-7K3M-9Q2X",
        "APT--7K3M-9Q2X",
        "APT-7K3M-9Q2X-APT-7K3M-9Q2X-APT-7K3M"
      })
  void rejectsWhatCannotBeAReference(String typed) {
    assertThat(AppointmentReference.parse(typed)).isEmpty();
  }

  @Test
  void storedFormIsRequired() {
    assertThatIllegalArgumentException().isThrownBy(() -> new AppointmentReference("apt7k3m9q2x"));
  }
}
