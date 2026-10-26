package za.co.fynbos.branchbook.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Books, opens and cancels appointments through the API in tests. Bodies are written by Jackson, so
 * any value, even a malformed one, is sent exactly as given. The customer is the synthetic Nomvula
 * Dlamini unless a test says otherwise; numbers come from the demo range in SECURITY.md.
 */
public final class Bookings {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Bookings() {}

  /**
   * A booking body.
   *
   * @param branchCode the branch code
   * @param serviceCode the service code
   * @param startsAt the start, as sent
   * @param mobile the mobile number, as sent
   * @return the body's fields, which a test may change before sending
   */
  public static Map<String, Object> body(
      String branchCode, String serviceCode, String startsAt, @Nullable String mobile) {
    Map<String, Object> customer = new LinkedHashMap<>();
    customer.put("firstName", "Nomvula");
    customer.put("lastName", "Dlamini");
    customer.put("mobile", mobile);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("branchCode", branchCode);
    body.put("serviceCode", serviceCode);
    body.put("startsAt", startsAt);
    body.put("customer", customer);
    return body;
  }

  /**
   * Returns the customer part of a body, to change it.
   *
   * @param body a body from {@link #body}
   * @return its customer
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> customer(Map<String, Object> body) {
    return (Map<String, Object>) body.get("customer");
  }

  /**
   * Books with a fresh {@code Idempotency-Key}.
   *
   * @param mvc the application
   * @param body the body
   * @return the response
   */
  public static MvcTestResult book(MockMvcTester mvc, Map<String, Object> body) {
    return book(mvc, UUID.randomUUID().toString(), body);
  }

  /**
   * Books with the given {@code Idempotency-Key}.
   *
   * @param mvc the application
   * @param key the header's value, or {@code null} to leave it out
   * @param body the body
   * @return the response
   */
  public static MvcTestResult book(
      MockMvcTester mvc, @Nullable String key, Map<String, Object> body) {
    MockMvcTester.MockMvcRequestBuilder request =
        mvc.post()
            .uri("/api/v1/appointments")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Correlation-Id", "test-booking")
            .content(JSON.writeValueAsString(body));
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return request.exchange();
  }

  /**
   * Opens an appointment, as the manage page does.
   *
   * @param mvc the application
   * @param reference the reference, as sent
   * @param last4 the {@code X-Mobile-Last4} header, or {@code null} to leave it out
   * @return the response
   */
  public static MvcTestResult lookup(MockMvcTester mvc, String reference, @Nullable String last4) {
    MockMvcTester.MockMvcRequestBuilder request =
        mvc.get()
            .uri("/api/v1/appointments/{reference}", reference)
            .header("X-Correlation-Id", "test-lookup");
    if (last4 != null) {
      request.header("X-Mobile-Last4", last4);
    }
    return request.exchange();
  }

  /**
   * Cancels an appointment, as the manage page does.
   *
   * @param mvc the application
   * @param reference the reference, as sent
   * @param last4 the {@code X-Mobile-Last4} header, or {@code null} to leave it out
   * @return the response
   */
  public static MvcTestResult cancel(MockMvcTester mvc, String reference, @Nullable String last4) {
    MockMvcTester.MockMvcRequestBuilder request =
        mvc.post()
            .uri("/api/v1/appointments/{reference}/cancel", reference)
            .header("X-Correlation-Id", "test-cancel");
    if (last4 != null) {
      request.header("X-Mobile-Last4", last4);
    }
    return request.exchange();
  }
}
