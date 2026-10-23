package za.co.fynbos.branchbook.catalogue.infrastructure;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import za.co.fynbos.branchbook.catalogue.application.CatalogueReadPort;
import za.co.fynbos.branchbook.catalogue.domain.Address;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.OperatingHours;
import za.co.fynbos.branchbook.catalogue.domain.Province;
import za.co.fynbos.branchbook.catalogue.domain.PublicHoliday;
import za.co.fynbos.branchbook.catalogue.domain.ServiceType;

/**
 * Reads the catalogue tables with plain SQL through {@link JdbcClient}.
 *
 * <p>The catalogue is read-only reference data, so it is mapped to immutable records rather than
 * JPA entities. That keeps Hibernate's schema validation to the one table it manages ({@code
 * appointments}) and lets {@code documents_needed}, a PostgreSQL text array, be read without any
 * custom type mapping.
 */
@Repository
class JdbcCatalogue implements CatalogueReadPort {

  private static final String BRANCH_COLUMNS =
      """
      id, code, name, sms_name, address_line1, suburb, city, province, postal_code, timezone,
      open_on_public_holidays""";

  private final JdbcClient jdbc;

  JdbcCatalogue(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Branch> activeBranches() {
    return jdbc.sql("SELECT " + BRANCH_COLUMNS + " FROM branches WHERE active ORDER BY name")
        .query((rs, row) -> branch(rs))
        .list();
  }

  @Override
  public Optional<Branch> activeBranch(String code) {
    return jdbc.sql("SELECT " + BRANCH_COLUMNS + " FROM branches WHERE active AND code = :code")
        .param("code", code)
        .query((rs, row) -> branch(rs))
        .optional();
  }

  @Override
  public List<OperatingHours> operatingHours(UUID branchId) {
    return jdbc.sql(
            """
            SELECT weekday, opens_at, closes_at FROM branch_operating_hours
            WHERE branch_id = :branchId ORDER BY weekday""")
        .param("branchId", branchId)
        .query((rs, row) -> operatingHours(rs))
        .list();
  }

  @Override
  public Map<UUID, List<OperatingHours>> operatingHoursOfActiveBranches() {
    return jdbc
        .sql(
            """
            SELECT h.branch_id, h.weekday, h.opens_at, h.closes_at
            FROM branch_operating_hours h JOIN branches b ON b.id = h.branch_id
            WHERE b.active ORDER BY h.branch_id, h.weekday""")
        .query((rs, row) -> Map.entry(rs.getObject("branch_id", UUID.class), operatingHours(rs)))
        .list()
        .stream()
        .collect(
            Collectors.groupingBy(
                Map.Entry::getKey,
                LinkedHashMap::new,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
  }

  @Override
  public List<ServiceType> servicesOfferedAt(UUID branchId) {
    return jdbc.sql(
            """
            SELECT s.id, s.code, s.name, s.sms_name, s.description, s.duration_minutes,
                   s.documents_needed
            FROM services s JOIN branch_services bs ON bs.service_id = s.id
            WHERE bs.branch_id = :branchId AND s.active
            ORDER BY s.sort_order""")
        .param("branchId", branchId)
        .query(
            (rs, row) ->
                new ServiceType(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("sms_name"),
                    rs.getString("description"),
                    rs.getInt("duration_minutes"),
                    textArray(rs.getArray("documents_needed"))))
        .list();
  }

  @Override
  public List<PublicHoliday> publicHolidays() {
    return jdbc.sql("SELECT holiday_date, name, source FROM public_holidays ORDER BY holiday_date")
        .query(
            (rs, row) ->
                new PublicHoliday(
                    rs.getObject("holiday_date", LocalDate.class),
                    rs.getString("name"),
                    PublicHoliday.Source.valueOf(rs.getString("source"))))
        .list();
  }

  private static Branch branch(ResultSet rs) throws SQLException {
    Address address =
        new Address(
            rs.getString("address_line1"),
            rs.getString("suburb"),
            rs.getString("city"),
            Province.fromDisplayName(rs.getString("province")),
            rs.getString("postal_code"));
    return new Branch(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("sms_name"),
        address,
        // An unknown zone fails here, when the branch is read, rather than later in a calculation.
        ZoneId.of(rs.getString("timezone")),
        rs.getBoolean("open_on_public_holidays"));
  }

  private static OperatingHours operatingHours(ResultSet rs) throws SQLException {
    return new OperatingHours(
        DayOfWeek.of(rs.getInt("weekday")),
        rs.getObject("opens_at", LocalTime.class),
        rs.getObject("closes_at", LocalTime.class));
  }

  private static List<String> textArray(Array array) throws SQLException {
    try {
      return Arrays.asList((String[]) array.getArray());
    } finally {
      array.free();
    }
  }
}
