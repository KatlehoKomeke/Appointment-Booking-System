package za.co.fynbos.branchbook.shared.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Period;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The booking rules that are configuration rather than code, under {@code branchbook.booking}.
 * application.yml sets them and explains each value; the application refuses to start without them.
 *
 * @param leadTime how far ahead of now the earliest bookable slot starts (BR-4)
 * @param horizon how many days are bookable, counted from today in the branch's time zone (BR-5)
 */
@Validated
@ConfigurationProperties("branchbook.booking")
public record BookingProperties(@NotNull Duration leadTime, @NotNull Period horizon) {}
