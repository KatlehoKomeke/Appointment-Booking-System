package za.co.fynbos.branchbook.booking.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import za.co.fynbos.branchbook.booking.domain.DemoNumberBlock;
import za.co.fynbos.branchbook.shared.config.BookingProperties;

/**
 * Makes the published demo numbers, {@code branchbook.booking.demo-number-block}, one bean that the
 * booking rule and the SMS routing share, so the numbers a public demo takes and the numbers it
 * never really texts can never drift apart (ADR-0006).
 */
@Configuration(proxyBeanMethods = false)
class DemoNumberBlockConfiguration {

  /**
   * Reads the block from configuration.
   *
   * @param booking the booking settings
   * @return the block; start-up fails if either end is not a South African mobile number in E.164
   *     form, or the first comes after the last
   */
  @Bean
  DemoNumberBlock demoNumberBlock(BookingProperties booking) {
    return DemoNumberBlock.of(booking.demoNumberBlock().first(), booking.demoNumberBlock().last());
  }
}
