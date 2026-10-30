package za.co.fynbos.branchbook.shared.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * HTTP security: every request is allowed, and every response carries the browser's defences.
 *
 * <p>BranchBook has no logins (docs/adr/0004-restraint-no-login-no-id-number.md). A customer proves
 * an appointment is theirs with its reference and the last four digits of their mobile number, and
 * the booking module checks that proof per request. Nothing is restricted at this layer, so this
 * class concentrates on what the browser should enforce:
 *
 * <ul>
 *   <li><b>No sessions and no CSRF tokens.</b> The application never sets or reads a cookie, so a
 *       forged cross-site request carries no credential to abuse. CSRF tokens would protect nothing
 *       and add a round trip to every booking.
 *   <li><b>A strict Content Security Policy.</b> Scripts, styles, fonts and API calls may come only
 *       from this origin, so injected markup can neither load nor run anything else.
 *   <li><b>No framing,</b> so the booking pages cannot be overlaid by another site (clickjacking).
 *   <li><b>No referrer.</b> App URLs such as {@code /appointments/APT-7K3M-9Q2X} contain a booking
 *       reference, which must not leak to other sites through the {@code Referer} header.
 *   <li><b>No powerful browser features.</b> The app needs no camera, microphone, location or
 *       payment API, so the Permissions Policy switches them off.
 * </ul>
 *
 * <p>Spring Security's other defaults stay on: {@code X-Content-Type-Options: nosniff}, {@code
 * Cache-Control: no-store} and, over HTTPS, {@code Strict-Transport-Security}.
 *
 * <p>One exception: the Swagger UI pages under {@code /swagger-ui/} set inline styles, so their
 * policy also allows {@code style-src 'unsafe-inline'}. Scripts stay limited to this origin there
 * too, and every other path, the app and the API included, keeps the strict policy.
 *
 * <p>It exists only in a web application: a migrate-only run serves nothing.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig {

  /**
   * The Content Security Policy. {@code img-src} also allows {@code data:} because the build
   * inlines small images as data URIs. {@code frame-ancestors} repeats the framing ban for browsers
   * that honour only CSP.
   */
  static final String CONTENT_SECURITY_POLICY =
      "default-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  /**
   * The policy for the Swagger UI pages only: the strict policy, plus inline styles, which Swagger
   * UI's rendering needs. Inline scripts are still refused.
   */
  static final String SWAGGER_UI_CONTENT_SECURITY_POLICY =
      CONTENT_SECURITY_POLICY + "; style-src 'self' 'unsafe-inline'";

  /** Browser features the app never uses, switched off for this origin and anything it embeds. */
  static final String PERMISSIONS_POLICY =
      "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

  /**
   * Builds the one filter chain that every request passes through.
   *
   * @param http Spring Security's builder for the chain
   * @return the chain, applied to every path
   * @throws Exception if Spring Security rejects the configuration at startup
   */
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    RequestMatcher swaggerUi = PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui/**");
    return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .headers(
            headers ->
                headers
                    .addHeaderWriter(
                        new DelegatingRequestMatcherHeaderWriter(
                            new NegatedRequestMatcher(swaggerUi),
                            new ContentSecurityPolicyHeaderWriter(CONTENT_SECURITY_POLICY)))
                    .addHeaderWriter(
                        new DelegatingRequestMatcherHeaderWriter(
                            swaggerUi,
                            new ContentSecurityPolicyHeaderWriter(
                                SWAGGER_UI_CONTENT_SECURITY_POLICY)))
                    .frameOptions(frame -> frame.deny())
                    .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                    .permissionsPolicyHeader(policy -> policy.policy(PERMISSIONS_POLICY)))
        .build();
  }
}
