package za.co.fynbos.branchbook.shared.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
 * @param lookupMaxFailures how many wrong lookups of one reference are answered before it is
 *     blocked (BR-13)
 * @param lookupWindow how long a reference stays blocked, counted from its first wrong lookup
 * @param demoNumbersOnly whether a booking must use one of the published demo numbers, so a public
 *     demo can never be given a real person's number (ADR-0006); off unless a deployment switches
 *     it on
 */
@Validated
@ConfigurationProperties("branchbook.booking")
public record BookingProperties(
    @NotNull Duration leadTime,
    @NotNull Period horizon,
    @Positive int lookupMaxFailures,
    @NotNull Duration lookupWindow,
    boolean demoNumbersOnly) {}
