package za.co.fynbos.branchbook.catalogue.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.ServiceType;

/**
 * Reads the branch and the service an appointment refers to, so the booking module can show them:
 * names, address, duration and the documents to bring. Each read finds the row whether or not it is
 * still active, because an appointment keeps the branch and service it was booked for.
 */
@Service
public class CatalogueLookup {

  private final CatalogueReadPort catalogue;

  CatalogueLookup(CatalogueReadPort catalogue) {
    this.catalogue = catalogue;
  }

  /**
   * Returns a branch by id.
   *
   * @param branchId the id an appointment holds
   * @return the branch
   * @throws IllegalStateException if no branch has that id, which the database's foreign key rules
   *     out for any stored appointment
   */
  @Transactional(readOnly = true)
  public Branch branch(UUID branchId) {
    return catalogue
        .branchById(branchId)
        .orElseThrow(() -> new IllegalStateException("No branch has the id an appointment holds"));
  }

  /**
   * Returns a service by id.
   *
   * @param serviceId the id an appointment holds
   * @return the service
   * @throws IllegalStateException if no service has that id, which the database's foreign key rules
   *     out for any stored appointment
   */
  @Transactional(readOnly = true)
  public ServiceType service(UUID serviceId) {
    return catalogue
        .serviceById(serviceId)
        .orElseThrow(() -> new IllegalStateException("No service has the id an appointment holds"));
  }
}
