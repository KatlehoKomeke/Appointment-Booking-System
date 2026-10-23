package za.co.fynbos.branchbook.support;

import java.util.UUID;

/** The fixed ids of the reference data that V3__reference_data.sql seeds. */
public final class SeedData {

  /** Sandton Summit Mall: open on Sundays; three seats for FICA and credit. */
  public static final UUID SANDTON = branch(1);

  /** Bellville Kingsway. */
  public static final UUID BELLVILLE = branch(2);

  /** Gqeberha Baywater: offers no home loans. */
  public static final UUID GQEBERHA = branch(4);

  /** Rustenburg Bojanala: closes at 12:00 on Saturdays. */
  public static final UUID RUSTENBURG = branch(6);

  /** FICA update, 15 minutes. */
  public static final UUID FICA = service(1);

  /** Card replacement, 15 minutes. */
  public static final UUID CARD = service(2);

  /** Home loan consultation, 45 minutes. */
  public static final UUID HOME_LOAN = service(6);

  private SeedData() {}

  private static UUID branch(int number) {
    return UUID.fromString("10000000-0000-4000-8000-00000000000" + number);
  }

  private static UUID service(int number) {
    return UUID.fromString("20000000-0000-4000-8000-00000000000" + number);
  }
}
