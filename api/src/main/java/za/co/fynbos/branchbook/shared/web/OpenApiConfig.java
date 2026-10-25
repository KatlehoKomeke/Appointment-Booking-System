package za.co.fynbos.branchbook.shared.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;

/**
 * Describes the API for springdoc, which generates the OpenAPI document that is committed as
 * docs/openapi.yaml and from which the web app's types are generated (ADR-0005).
 *
 * <ul>
 *   <li>The document is the stable contract {@code v1}, not the build version, so it changes only
 *       when the API does.
 *   <li>Every error is the hand-written {@code Problem} schema, which operations reference through
 *       {@link #PROBLEM}. Its {@code code} lists every {@link ProblemCode}, so the web app's type
 *       for it is the exact set of codes.
 *   <li>Every property of a response is marked required, because the API always sends every field;
 *       a field that can be empty is declared as nullable instead, and a nullable enum lists {@code
 *       null} among its values. The generated TypeScript types are therefore exact.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
// swagger-core's model API returns and accepts the raw Schema type, so building schemas cannot be
// type-checked; the suppression is limited to this class.
@SuppressWarnings({"rawtypes", "unchecked"})
public class OpenApiConfig {

  /** The reference to the problem schema, for {@code @Schema(ref = PROBLEM)} on error responses. */
  public static final String PROBLEM = "#/components/schemas/Problem";

  private static final String PROBLEM_NAME = "Problem";

  /** Created by Spring; public only so other packages can use {@link #PROBLEM}. */
  OpenApiConfig() {}

  /**
   * The document's description, server and shared schemas.
   *
   * @return the base of the generated document
   */
  @Bean
  OpenAPI branchBookOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("BranchBook API")
                .version("v1")
                .description(
                    "Branch appointment booking for the fictional Fynbos Bank. Every error is an RFC"
                        + " 9457 problem, and every error the API returns on purpose carries a stable"
                        + " code, listed in docs/errors.md. Besides the errors each operation lists,"
                        + " any operation can return 500 INTERNAL_ERROR or 503 TRY_AGAIN.")
                .license(new License().name("MIT").url("https://opensource.org/license/mit")))
        .servers(List.of(new Server().url("/").description("The origin that serves the app")))
        .components(new Components().addSchemas(PROBLEM_NAME, problemSchema()));
  }

  /**
   * Marks every property of every generated response schema as required (see the class comment).
   *
   * @return the customizer springdoc applies to the finished document
   */
  @Bean
  GlobalOpenApiCustomizer everyResponsePropertyIsSent() {
    return openApi ->
        openApi
            .getComponents()
            .getSchemas()
            .forEach(
                (name, schema) -> {
                  if (!PROBLEM_NAME.equals(name) && schema.getProperties() != null) {
                    schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
                  }
                });
  }

  /**
   * Lists {@code null} among the values of every nullable enum, such as {@code closedReason}. JSON
   * Schema checks {@code type} and {@code enum} separately, so without it a validator, or a client
   * generated from the document, would refuse the {@code null} the API sends.
   *
   * @return the customizer springdoc applies to the finished document
   */
  @Bean
  GlobalOpenApiCustomizer nullableEnumsAllowNull() {
    return openApi ->
        openApi
            .getComponents()
            .getSchemas()
            .values()
            .forEach(
                schema -> {
                  if (schema.getProperties() != null) {
                    schema.getProperties().values().forEach(OpenApiConfig::allowNullInEnum);
                  }
                });
  }

  private static void allowNullInEnum(Object property) {
    Schema schema = (Schema) property;
    Set<String> types = schema.getTypes();
    List<Object> values = schema.getEnum();
    if (types != null && types.contains("null") && values != null && !values.contains(null)) {
      List<Object> withNull = new ArrayList<>(values);
      withNull.add(null);
      schema.setEnum(withNull);
    }
  }

  /** The RFC 9457 problem body that ProblemDetailHandler writes (docs/errors.md). */
  private static Schema problemSchema() {
    Schema fieldProblem =
        object(
            Map.of(
                "field", nullableString("The field, parameter or header with the problem"),
                "code", string("The rule it broke, for example PATTERN"),
                "message", nullableString("A short explanation that never quotes the value")),
            List.of("field", "code", "message"));
    Schema alternative =
        object(
            Map.of(
                "startsAt", dateTime("When the alternative slot starts"),
                "endsAt", dateTime("When it ends")),
            List.of("startsAt", "endsAt"));
    return object(
            Map.of(
                "type",
                    string(
                        "urn:branchbook:problem: followed by the code; absent, meaning"
                            + " about:blank, when there is no code"),
                "title", string("A short, plain-language title"),
                "status", new Schema().types(Set.of("integer")).description("The HTTP status"),
                "detail", string("A plain-language explanation; never quotes the request"),
                "instance", string("urn:branchbook:request: followed by the correlation id"),
                "code",
                    string(
                            "The stable error code, one of those in docs/errors.md. Absent only on"
                                + " HTTP-level failures the API never returns on purpose: 404 for a"
                                + " path it does not serve, 405, 406 and 415")
                        ._enum(Arrays.stream(ProblemCode.values()).map(ProblemCode::name).toList()),
                "correlationId", string("Matches the X-Correlation-Id header and the server logs"),
                "errors",
                    new Schema()
                        .types(Set.of("array"))
                        .items(fieldProblem)
                        .description("For VALIDATION_FAILED: what is wrong with each field"),
                "alternatives",
                    new Schema()
                        .types(Set.of("array"))
                        .items(alternative)
                        .description("For SLOT_FULL: up to three other slots")),
            List.of("title", "status", "instance", "correlationId"))
        .description(
            "An RFC 9457 problem. The web app chooses its wording from code, never detail.");
  }

  private static Schema object(Map<String, Schema> properties, List<String> required) {
    Schema schema = new Schema().types(Set.of("object"));
    properties.keySet().stream()
        .sorted()
        .forEach(name -> schema.addProperty(name, properties.get(name)));
    schema.setRequired(required);
    return schema;
  }

  private static Schema string(String description) {
    return new Schema().types(Set.of("string")).description(description);
  }

  /**
   * A string that may be null. The types are kept in a fixed order: {@code Set.of} iterates in an
   * order that changes between runs, which would make the generated document differ every build.
   */
  private static Schema nullableString(String description) {
    return new Schema()
        .types(new LinkedHashSet<>(List.of("string", "null")))
        .description(description);
  }

  private static Schema dateTime(String description) {
    return new Schema().types(Set.of("string")).format("date-time").description(description);
  }
}
