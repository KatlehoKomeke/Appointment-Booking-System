package za.co.fynbos.branchbook.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Switches on the scheduled housekeeping jobs ({@code branchbook.jobs}), such as the hourly purges
 * and the daily holiday-coverage warning (BR-3). They read time from the injected clock like
 * everything else; the schedule only decides when they run.
 *
 * <p>A migrate-only run ({@link MigrateOnlyMode}) starts no scheduler: it migrates, validates and
 * exits, and must not purge or warn on the way.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(
    name = MigrateOnlyMode.PROPERTY,
    havingValue = false,
    matchIfMissing = true)
@EnableScheduling
class SchedulingConfiguration {}
