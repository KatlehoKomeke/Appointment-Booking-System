package za.co.fynbos.branchbook.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * How a typed name is normalised, and which names are refused. Characters that look alike, or are
 * invisible, are named constants rather than literals, so a reviewer can tell them apart.
 */
class CustomerNameTest {

  private static final String RIGHT_SINGLE_QUOTATION_MARK = Character.toString(0x2019);
  private static final String COMBINING_DIAERESIS = Character.toString(0x0308);
  private static final String E_WITH_DIAERESIS = Character.toString(0x00EB);
  private static final String S_WITH_CARON = Character.toString(0x0161);
  private static final String NO_BREAK_SPACE = Character.toString(0x00A0);
  private static final String EM_SPACE = Character.toString(0x2003);

  /** A letter outside the Basic Multilingual Plane: one character, two UTF-16 units. */
  private static final String MATHEMATICAL_SCRIPT_CAPITAL_A = Character.toString(0x1D49C);

  /** iOS types a curly apostrophe; it is stored as the plain one. */
  @Test
  void acceptsCurlyApostropheFromIos() {
    assertThat(CustomerName.parse("O" + RIGHT_SINGLE_QUOTATION_MARK + "Brien"))
        .map(CustomerName::value)
        .contains("O'Brien");
  }

  /** A letter typed with a separate combining mark is composed into one character (NFC). */
  @Test
  void acceptsCombiningDiaeresis() {
    assertThat(CustomerName.parse("Zoe" + COMBINING_DIAERESIS))
        .map(CustomerName::value)
        .contains("Zo" + E_WITH_DIAERESIS);
    assertThat(CustomerName.parse("Nt" + S_WITH_CARON + "hebe"))
        .map(CustomerName::value)
        .contains("Nt" + S_WITH_CARON + "hebe");
  }

  @Test
  void collapsesUnicodeSpacesAndCountsCodePoints() {
    String sixtyLetters = MATHEMATICAL_SCRIPT_CAPITAL_A.repeat(CustomerName.MAX_CODE_POINTS);

    assertThat(CustomerName.parse("  Mary" + NO_BREAK_SPACE + EM_SPACE + "Anne  "))
        .map(CustomerName::value)
        .contains("Mary Anne");
    assertThat(CustomerName.parse("van der Merwe"))
        .map(CustomerName::value)
        .contains("van der Merwe");
    assertThat(CustomerName.parse(sixtyLetters)).isPresent();
    assertThat(CustomerName.parse(sixtyLetters + MATHEMATICAL_SCRIPT_CAPITAL_A)).isEmpty();
  }

  @ParameterizedTest(name = "[{index}]")
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ",
        "Thandi2",
        "Th@ndi",
        "Thandi.",
        "Thandi\nMokoena",
        "Thandi\r",
        "Thandi\tMokoena",
        "-Thandi",
        "'Thandi"
      })
  void rejectsDigitsPunctuationAndLineBreaks(String typed) {
    assertThat(CustomerName.parse(typed)).isEmpty();
  }

  @Test
  void onlySpacesIsNoName() {
    assertThat(CustomerName.parse(NO_BREAK_SPACE + EM_SPACE)).isEmpty();
  }

  @Test
  void toStringNeverShowsTheName() {
    assertThat(String.valueOf(CustomerName.parse("Thandi").orElseThrow()))
        .isEqualTo("CustomerName[redacted]");
  }
}
