/**
 * BranchBook, the branch appointment booking system of the fictional Fynbos Bank.
 *
 * <p>The application is a modular monolith (docs/adr/0001-modular-monolith.md), packaged by
 * capability:
 *
 * <ul>
 *   <li>{@code catalogue}: branches, services, trading hours, capacities and public holidays.
 *   <li>{@code scheduling}: the slot calendar.
 *   <li>{@code booking}: appointments, the booking transaction, lookup and cancellation.
 *   <li>{@code notification}: the confirmation outbox, its dispatcher and the simulated SMS
 *       gateway.
 *   <li>{@link za.co.fynbos.branchbook.shared}: concerns every module needs, such as HTTP security.
 * </ul>
 *
 * <p>This root package holds only the entry point, {@link
 * za.co.fynbos.branchbook.BranchBookApplication}. Modules are added milestone by milestone; the
 * structure and the dependency rule between them are described in docs/architecture.md, section 3.
 */
package za.co.fynbos.branchbook;
