package za.co.fynbos.branchbook.notification.domain;

/**
 * The GSM 03.38 alphabet that a single-part SMS is encoded in, and how many 7-bit septets a text
 * takes (BR-18).
 *
 * <p>A text made only of these characters fits 160 septets in one segment. Characters of the basic
 * set take one septet; those of the extension table ({@code ^ { } \ [ ~ ] |}, the euro sign and
 * form feed) take two, because each is sent after an escape. Any other character, such as a curly
 * apostrophe, would switch the whole message to UCS-2 and cut a segment to 70 characters.
 */
public final class Gsm7 {

  /** The most septets one SMS segment carries. */
  public static final int SINGLE_SEGMENT = 160;

  /** The basic character set, one septet each. The escape character itself is left out. */
  private static final String BASIC =
      "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
          + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

  /** The extension table, two septets each: an escape, then the character. */
  private static final String EXTENSION = "\f^{}\\[~]|€";

  private Gsm7() {}

  /**
   * Tells whether a text can be sent in the GSM 7-bit alphabet.
   *
   * @param text the text
   * @return {@code true} if every character is in the basic set or the extension table
   */
  public static boolean isEncodable(CharSequence text) {
    return text.chars().allMatch(c -> BASIC.indexOf(c) >= 0 || EXTENSION.indexOf(c) >= 0);
  }

  /**
   * Counts the septets a text takes.
   *
   * @param text the text
   * @return one per basic character and two per extension character
   * @throws IllegalArgumentException if the text has a character outside the alphabet
   */
  public static int septets(CharSequence text) {
    int septets = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (BASIC.indexOf(c) >= 0) {
        septets += 1;
      } else if (EXTENSION.indexOf(c) >= 0) {
        septets += 2;
      } else {
        throw new IllegalArgumentException("The text has a character outside the GSM-7 alphabet");
      }
    }
    return septets;
  }
}
