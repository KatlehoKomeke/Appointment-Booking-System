package za.co.fynbos.branchbook.booking.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The demo data setting, under {@code branchbook.demo-data} in application.yml (FR-28).
 *
 * @param enabled whether {@link DemoDataLoader} books its three fixtures when the application has
 *     started on an empty database; off by default, and switched on only by docker compose ({@code
 *     BRANCHBOOK_DEMO_DATA_ENABLED}), so no developer's or test's database is ever filled behind
 *     their back
 */
@ConfigurationProperties("branchbook.demo-data")
record DemoDataProperties(boolean enabled) {}
