/**
 * The {@code shared} module: concerns that every other module relies on and none owns.
 *
 * <p>It holds error responses ({@link za.co.fynbos.branchbook.shared.problem}), correlation ids
 * ({@link za.co.fynbos.branchbook.shared.correlation}), the clock ({@link
 * za.co.fynbos.branchbook.shared.time}), typed settings ({@link
 * za.co.fynbos.branchbook.shared.config}), HTTP security ({@link
 * za.co.fynbos.branchbook.shared.security}) and the serving of the React app ({@link
 * za.co.fynbos.branchbook.shared.web}). Any module may depend on {@code shared}, and {@code shared}
 * depends on no module, so it can never become a back door between them (docs/architecture.md,
 * section 3).
 */
package za.co.fynbos.branchbook.shared;
