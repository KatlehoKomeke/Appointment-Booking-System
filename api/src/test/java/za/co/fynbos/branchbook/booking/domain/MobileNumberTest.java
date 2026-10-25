package za.co.fynbos.branchbook.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * BR-19: which numbers are South African mobiles, and how one is shown. The numbers are synthetic,
 * from the demo range in SECURITY.md.
 */
class MobileNumberTest {

  private static final PhoneNumberUtil PHONE_NUMBERS = PhoneNumberUtil.getInstance();

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "082 555 0101",
        "0825550101",
        "+27 82 555 0101",
        "+27 (0)82 555 0101",
        "27825550101",
        "0027825550101"
      })
  void sixInputFormatsNormaliseToPlus27825550101(String typed) {
    assertThat(MobileNumber.parse(typed)).map(MobileNumber::e164).contains("+27825550101");
  }

  /**
   * An SMS cannot reach a landline, a VoIP number or a toll-free number. The test also asserts the
   * type libphonenumber reports for each, so a change in its metadata fails here, not in a booking.
   */
  @Test
  void rejectsLandline021Voip087AndTollFree080() throws Exception {
    String landline = "021 555 0101";
    String voip = example(PhoneNumberType.VOIP);
    String tollFree = example(PhoneNumberType.TOLL_FREE);

    assertThat(type(landline)).isEqualTo(PhoneNumberType.FIXED_LINE);
    assertThat(voip).startsWith("087");
    assertThat(type(voip)).isEqualTo(PhoneNumberType.VOIP);
    assertThat(tollFree).startsWith("080");
    assertThat(type(tollFree)).isEqualTo(PhoneNumberType.TOLL_FREE);
    assertThat(MobileNumber.parse(landline)).isEmpty();
    assertThat(MobileNumber.parse(voip)).isEmpty();
    assertThat(MobileNumber.parse(tollFree)).isEmpty();
  }

  /** libphonenumber's own example of a South African mobile is one the database will store. */
  @Test
  void libphonenumberZaExampleMobileMatchesStorageCheck() {
    PhoneNumber example = PHONE_NUMBERS.getExampleNumberForType("ZA", PhoneNumberType.MOBILE);
    String e164 = PHONE_NUMBERS.format(example, PhoneNumberFormat.E164);

    assertThat(e164).matches(MobileNumber.E164_PATTERN);
    assertThat(MobileNumber.parse(e164)).map(MobileNumber::e164).contains(e164);
  }

  @Test
  void rendersDisplayMaskedRedactedAndLastFour() {
    MobileNumber mobile = MobileNumber.ofE164("+27825550101");

    assertThat(mobile.display()).isEqualTo("082 555 0101");
    assertThat(mobile.masked()).isEqualTo("082 *** 0101");
    assertThat(mobile.redacted()).isEqualTo("082 *** ****");
    assertThat(mobile.last4()).isEqualTo("0101");
  }

  @ParameterizedTest(name = "{0}")
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ",
        "+44 7911 123456",
        "082 555 0101 ext 12",
        "tel:+27825550101",
        "082-FYNBOS-1",
        "082 555 010",
        "082 555 01011",
        "0825550101                         "
      })
  void rejectsForeignNumbersExtensionsLettersAndWrongLengths(String typed) {
    assertThat(MobileNumber.parse(typed)).isEmpty();
  }

  @Test
  void numbersTypedDifferentlyAreEqual() {
    assertThat(MobileNumber.parse("082 555 0101")).isEqualTo(MobileNumber.parse("+27825550101"));
  }

  /** A stored number is checked against the stored form only, never parsed again. */
  @Test
  void storedFormMustMatchTheSchemaPattern() {
    assertThat(MobileNumber.ofE164("+27825550101").e164()).isEqualTo("+27825550101");
    assertThatIllegalArgumentException().isThrownBy(() -> MobileNumber.ofE164("0825550101"));
    assertThatIllegalArgumentException().isThrownBy(() -> MobileNumber.ofE164("+27215550101"));
  }

  @Test
  void toStringNeverShowsTheDigits() {
    String text = "number " + MobileNumber.ofE164("+27825550101");

    assertThat(text).isEqualTo("number 082 *** ****").doesNotContain("555").doesNotContain("0101");
  }

  private static String example(PhoneNumberType type) {
    return PHONE_NUMBERS
        .format(PHONE_NUMBERS.getExampleNumberForType("ZA", type), PhoneNumberFormat.NATIONAL)
        .replace(" ", "");
  }

  private static PhoneNumberType type(String number) throws Exception {
    return PHONE_NUMBERS.getNumberType(PHONE_NUMBERS.parse(number, "ZA"));
  }
}
