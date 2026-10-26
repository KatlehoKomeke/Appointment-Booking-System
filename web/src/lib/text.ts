/**
 * Small pieces of customer copy that more than one screen builds the same way.
 *
 * @packageDocumentation
 */

/**
 * A count with its word, singular for one: "1 attempt", "5 attempts".
 *
 * @param count - how many
 * @param word - the singular word; the plural adds an "s"
 */
export function plural(count: number, word: string): string {
  return `${String(count)} ${word}${count === 1 ? '' : 's'}`;
}

/** The parts of a branch's address that {@link addressLine} uses. */
export interface AddressParts {
  /** The street address. */
  line1: string;
  /** The suburb. */
  suburb: string;
  /** The postal code. */
  postalCode: string;
}

/**
 * A branch's address as the booking steps write it: the street, the suburb and the postal code. The city is on the
 * branch list.
 *
 * @param address - the branch's address
 */
export function addressLine(address: AddressParts): string {
  return [address.line1, address.suburb, address.postalCode].join(', ');
}
