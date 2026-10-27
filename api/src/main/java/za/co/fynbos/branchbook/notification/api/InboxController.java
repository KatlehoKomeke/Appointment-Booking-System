package za.co.fynbos.branchbook.notification.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.fynbos.branchbook.notification.api.InboxResponse.InboxMessageResponse;
import za.co.fynbos.branchbook.notification.application.InboxMessage;
import za.co.fynbos.branchbook.notification.application.InboxProperties;
import za.co.fynbos.branchbook.notification.application.InboxQueries;
import za.co.fynbos.branchbook.notification.application.RetryMessageHandler;
import za.co.fynbos.branchbook.shared.web.OpenApiConfig;

/**
 * The simulated inbox, an operator view of every booking's messages (FR-17), and the retry of a
 * message that failed (FR-18). No real message is ever sent, and recipients are redacted. Both
 * exist only while {@code branchbook.inbox.enabled} is true, so a deployment that does not ask for
 * them has no such paths, and an operator there replays a message with the SQL in docs/runbook.md.
 * Where {@code branchbook.inbox.redact-references} is true too, both show every appointment
 * reference as {@code APT-****-****}, so a public inbox opens no booking.
 */
@RestController
@RequestMapping(path = "/api/v1/messages", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "branchbook.inbox.enabled", havingValue = "true")
@Tag(
    name = "Simulated inbox",
    description = "Every booking's simulated messages, recipients redacted, and their retry.")
class InboxController {

  private static final String REFERENCES_HIDDEN =
      " Where branchbook.inbox.redact-references is true, every appointment reference, in the"
          + " reference field and in the text, shows as APT-****-****.";

  private static final String UUID_PATTERN =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  private final InboxQueries inbox;
  private final RetryMessageHandler retries;
  private final InboxProperties properties;

  InboxController(InboxQueries inbox, RetryMessageHandler retries, InboxProperties properties) {
    this.inbox = inbox;
    this.retries = retries;
    this.properties = properties;
  }

  /**
   * Lists the newest messages of every appointment.
   *
   * @param limit how many to return, from 1 to 200
   * @return the messages, newest first
   */
  @Operation(
      operationId = "listMessages",
      summary = "List the simulated messages",
      description =
          "The newest messages of every appointment, with recipients redacted to 082 *** ****."
              + REFERENCES_HIDDEN
              + " Switched off unless branchbook.inbox.enabled is true; then the path is a 404.")
  @ApiResponse(responseCode = "200", description = "The messages, newest first")
  @ApiResponse(
      responseCode = "400",
      description = "VALIDATION_FAILED: limit is not from 1 to 200",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(ref = OpenApiConfig.PROBLEM)))
  @GetMapping
  InboxResponse list(
      @Parameter(description = "How many messages to return, from 1 to 200", example = "50")
          @RequestParam(defaultValue = "50")
          @Min(value = 1, message = "must be at least 1")
          @Max(value = 200, message = "must be at most 200")
          int limit) {
    return InboxResponse.from(inbox.newest(limit).stream().map(this::shown).toList());
  }

  /**
   * Makes a failed message due again, so the dispatcher sends it on its next tick.
   *
   * @param id the message's id, a UUID
   * @return the message as it is now: PENDING if it was reset, otherwise unchanged
   */
  @Operation(
      operationId = "retryMessage",
      summary = "Retry a failed simulated message",
      description =
          "Resets a FAILED message whose failureReason is DELIVERY_FAILED or DISPATCH_ERROR to"
              + " PENDING with no attempts, due now. Any other message, and the confirmation of an"
              + " appointment that is cancelled or has started, is returned unchanged with"
              + " retryable false. A cancellation is deleted once it is sent or has failed for"
              + " good, so its retry is then a 404. Waits up to 2 seconds for a send of the message"
              + " in flight to finish first, and keeps the message locked until it commits."
              + REFERENCES_HIDDEN
              + " Switched off unless branchbook.inbox.enabled is true; then the path is a 404.")
  @ApiResponse(
      responseCode = "200",
      description = "The message: reset to PENDING, or unchanged when a retry could not send it")
  @ApiResponse(
      responseCode = "400",
      description = "VALIDATION_FAILED: id is not a UUID",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(ref = OpenApiConfig.PROBLEM)))
  @ApiResponse(
      responseCode = "404",
      description =
          "MESSAGE_NOT_FOUND: no message has that id, or it was a cancellation, deleted once"
              + " final",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(ref = OpenApiConfig.PROBLEM)))
  @ApiResponse(
      responseCode = "503",
      description =
          "TRY_AGAIN: the message stayed locked for 2 seconds, or the database is unavailable",
      headers =
          @Header(
              name = "Retry-After",
              description = "Seconds to wait before trying again",
              schema = @Schema(type = "integer")),
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(ref = OpenApiConfig.PROBLEM)))
  @PostMapping("/{id}/retry")
  InboxMessageResponse retry(
      @Parameter(
              description = "The message's id, from the inbox",
              example = "dccc2431-28aa-4926-a555-ca6c80ddfd88")
          @PathVariable
          @Pattern(regexp = UUID_PATTERN, message = "must be a UUID")
          String id) {
    return InboxMessageResponse.from(shown(retries.retry(UUID.fromString(id))));
  }

  /** The message as this deployment shows it: with its references hidden, if it asks for that. */
  private InboxMessage shown(InboxMessage message) {
    return properties.redactReferences() ? message.withReferencesHidden() : message;
  }
}
