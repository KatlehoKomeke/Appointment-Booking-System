package za.co.fynbos.branchbook.booking.application;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The limiter's store as a map, for the handlers' unit tests: the port's five operations over a
 * {@link HashMap}, one thread at a time, with nothing to lock because there is no transaction.
 */
class InMemoryLookupAttemptStore implements LookupAttemptStore {

  private final Map<String, Window> windows = new HashMap<>();

  @Override
  public Optional<Window> lockWindow(String reference) {
    return Optional.ofNullable(windows.get(reference));
  }

  @Override
  public boolean insert(Window window) {
    return windows.putIfAbsent(window.reference(), window) == null;
  }

  @Override
  public void update(Window window) {
    windows.put(window.reference(), window);
  }

  @Override
  public void delete(String reference) {
    windows.remove(reference);
  }

  @Override
  public int purgeClosed(Instant now) {
    int before = windows.size();
    windows.values().removeIf(window -> !window.isOpenAt(now));
    return before - windows.size();
  }
}
