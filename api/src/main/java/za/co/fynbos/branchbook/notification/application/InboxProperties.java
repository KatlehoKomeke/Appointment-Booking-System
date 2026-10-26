package za.co.fynbos.branchbook.notification.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the simulated inbox (FR-17, FR-18), under {@code branchbook.inbox} in
 * application.yml.
 *
 * <p>A deployment with no sign-in, such as the hosted demo, hides references: a public inbox that
 * listed them, beside demo numbers whose last four digits are easily guessed, would let anyone open
 * or cancel another visitor's booking (BR-19).
 *
 * @param enabled whether the inbox and its retry exist; off by default, so their paths are a 404
 * @param redactReferences whether the inbox shows every appointment reference, in its own field and
 *     in the text of each message, as {@code APT-****-****}; off by default
 */
@ConfigurationProperties("branchbook.inbox")
public record InboxProperties(boolean enabled, boolean redactReferences) {}
