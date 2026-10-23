package za.co.fynbos.branchbook.catalogue.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A kind of appointment a customer can book, such as a card replacement (table {@code services}).
 * Each has a fixed duration and a list of documents to bring.
 *
 * @param id the service's id
 * @param code the short code the API uses, for example {@code CARD}
 * @param name the full name
 * @param smsName the short name used in the confirmation SMS, at most 18 characters
 * @param description one or two sentences on what the appointment is for
 * @param durationMinutes the appointment's length: a multiple of 15 minutes
 * @param documentsNeeded what the customer must bring, in display order
 */
public record ServiceType(
    UUID id,
    String code,
    String name,
    String smsName,
    String description,
    int durationMinutes,
    List<String> documentsNeeded) {

  /** Rejects missing values and keeps an unmodifiable copy of the documents. */
  public ServiceType {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(smsName, "smsName");
    Objects.requireNonNull(description, "description");
    documentsNeeded = List.copyOf(documentsNeeded);
  }
}
