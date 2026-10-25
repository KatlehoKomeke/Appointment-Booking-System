package za.co.fynbos.branchbook.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Marks an integration test: the whole application, on a real PostgreSQL 17 in Docker, with a
 * {@link MutableClock} and MockMvc.
 *
 * <p>Every integration test uses exactly this configuration, so Spring builds the context once and
 * one database container serves the whole run. Before every test the clock is back at {@link
 * TestClockConfiguration#DEFAULT_INSTANT}, so no test depends on the order tests run in.
 *
 * <p>References come from a {@link ScriptedReferenceGenerator}, which hands out random ones unless
 * a test scripts them. Classes that book through the API add {@link DatabaseReset}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@ExtendWith(ResetClockExtension.class)
@AutoConfigureMockMvc
@Import({
  TestcontainersConfiguration.class,
  TestClockConfiguration.class,
  TestReferenceConfiguration.class
})
public @interface IntegrationTest {}
