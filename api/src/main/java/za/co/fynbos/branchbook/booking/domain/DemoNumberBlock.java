package za.co.fynbos.branchbook.booking.domain;

/**
 * The published demo numbers, a block of South African mobiles printed in the README for anyone to
 * try: 082 555 0000 to 082 555 0199 unless a deployment says otherwise ({@code
 * branchbook.booking.demo-number-block}). A public demo that takes only these can never be given a
 * real person's number, so it never stores one; and because anyone may type them, and one may
 * belong to a real stranger, an SMS to one is always simulated (ADR-0006).
 *
 * <p>Every stored number has the same twelve characters, so comparing the E.164 text compares the
 * numbers.
 *
 * @param first the first number of the block, included
 * @param last the last number of the block, included
 */
public record DemoNumberBlock(MobileNumber first, MobileNumber last) {

  /** Refuses a block whose first number comes after its last. */
  public DemoNumberBlock {
    if (first == null || last == null || first.e164().compareTo(last.e164()) > 0) {
      throw new IllegalArgumentException(
          "The demo number block needs a first number that is not after its last");
    }
  }

  /**
   * Reads a block from its two ends in E.164 form, as configuration holds them.
   *
   * @param first the first number, such as {@code +27825550000}
   * @param last the last number, such as {@code +27825550199}
   * @return the block
   * @throws IllegalArgumentException if either end is not a South African mobile number in E.164
   *     form, or the first comes after the last
   */
  public static DemoNumberBlock of(String first, String last) {
    return new DemoNumberBlock(MobileNumber.ofE164(first), MobileNumber.ofE164(last));
  }

  /**
   * Tells whether a number is one of the published demo numbers.
   *
   * @param number the number to check
   * @return {@code true} if the number is in the block, both ends included
   */
  public boolean contains(MobileNumber number) {
    return number.e164().compareTo(first.e164()) >= 0 && number.e164().compareTo(last.e164()) <= 0;
  }

  /**
   * Names the block as South Africans write it, for a start-up log line. The demo numbers are
   * public, so they may be logged; no customer's number ever is.
   *
   * @return for example {@code 082 555 0000 to 082 555 0199}
   */
  @Override
  public String toString() {
    return first.display() + " to " + last.display();
  }
}
