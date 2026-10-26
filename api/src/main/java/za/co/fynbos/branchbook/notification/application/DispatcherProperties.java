package za.co.fynbos.branchbook.notification.application;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the confirmation dispatcher (BR-17), under {@code branchbook.dispatcher} in
 * application.yml.
 *
 * @param enabled whether the dispatcher runs on its schedule; the tests switch it off and drive
 *     {@link NotificationDispatcher#runOnce} themselves
 * @param interval the pause between the end of one tick and the start of the next
 * @param simulatedLatency how long the simulated gateway takes to accept a message
 */
@Validated
@ConfigurationProperties("branchbook.dispatcher")
public record DispatcherProperties(
    boolean enabled, @NotNull Duration interval, @NotNull Duration simulatedLatency) {}
