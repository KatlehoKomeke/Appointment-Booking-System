package za.co.fynbos.branchbook.support;

import java.util.regex.Pattern;

/**
 * Prepares a response body for a check that it holds none of a customer's digits. Such a check
 * looks for a short run like {@code 0184} or {@code 555} in the whole body, and the body also
 * carries random identifiers, UUIDs (message ids, generated correlation ids) and appointment
 * references, which contain such a run by chance often enough to fail a test now and then. Removing
 * them first keeps the check strict on everything else.
 */
public final class ResponseText {

  private static final Pattern UUID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private static final Pattern REFERENCE = Pattern.compile("APT-[A-Z0-9]{4}-[A-Z0-9]{4}");

  private ResponseText() {}

  /**
   * Returns the body with every UUID replaced by {@code <uuid>} and every appointment reference by
   * {@code <reference>}.
   *
   * @param body a response body
   * @return the body without its random identifiers
   */
  public static String withoutIdentifiers(String body) {
    return REFERENCE.matcher(UUID.matcher(body).replaceAll("<uuid>")).replaceAll("<reference>");
  }
}
