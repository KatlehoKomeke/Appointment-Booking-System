package za.co.fynbos.branchbook.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the reference generator with one a test can script. */
@TestConfiguration(proxyBeanMethods = false)
public class TestReferenceConfiguration {

  @Bean
  @Primary
  ScriptedReferenceGenerator scriptedReferenceGenerator() {
    return new ScriptedReferenceGenerator();
  }
}
