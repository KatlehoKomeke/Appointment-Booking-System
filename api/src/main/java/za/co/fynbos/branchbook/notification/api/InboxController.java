package za.co.fynbos.branchbook.notification.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.fynbos.branchbook.notification.application.InboxQueries;
import za.co.fynbos.branchbook.shared.web.OpenApiConfig;

/**
 * The simulated inbox, an operator view of every booking's messages (FR-17). No real message is
 * ever sent, and recipients are redacted. It exists only while {@code branchbook.inbox.enabled} is
 * true, so a deployment that does not ask for it has no such path.
 */
@RestController
@RequestMapping(path = "/api/v1/messages", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "branchbook.inbox.enabled", havingValue = "true")
@Tag(
    name = "Simulated inbox",
    description = "Every booking's simulated messages, recipients redacted.")
class InboxController {

  private final InboxQueries inbox;

  InboxController(InboxQueries inbox) {
    this.inbox = inbox;
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
    return InboxResponse.from(inbox.newest(limit));
  }
}
