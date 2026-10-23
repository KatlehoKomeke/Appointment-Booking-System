package za.co.fynbos.branchbook.shared.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id before anything else runs, including Spring Security.
 *
 * <p>The id comes from the caller's {@code X-Correlation-Id} header when it is acceptable (see
 * {@link CorrelationIds#acceptOrCreate}), otherwise it is generated. It is set on the response
 * straight away, so even a failed request carries it, and it is kept in the logging context for the
 * duration of the request only.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = CorrelationIds.acceptOrCreate(request.getHeader(CorrelationIds.HEADER));
    response.setHeader(CorrelationIds.HEADER, correlationId);
    MDC.put(CorrelationIds.MDC_KEY, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(CorrelationIds.MDC_KEY);
    }
  }
}
