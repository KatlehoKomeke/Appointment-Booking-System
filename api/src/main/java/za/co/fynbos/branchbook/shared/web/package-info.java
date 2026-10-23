/**
 * Serves the React app from the same origin as the API, so there is one deployable and no CORS
 * (docs/architecture.md, section 2).
 *
 * <p>The built app lives in the jar under {@code static/}: the Dockerfile copies it there before
 * packaging. Spring Boot serves {@code /index.html} at {@code /} and the hashed assets as they are;
 * {@link za.co.fynbos.branchbook.shared.web.SpaForwardingController} serves the same page for the
 * app's other routes.
 */
package za.co.fynbos.branchbook.shared.web;
