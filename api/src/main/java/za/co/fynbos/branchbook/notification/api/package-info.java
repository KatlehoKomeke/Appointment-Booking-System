/**
 * Notification's HTTP API: {@code GET /api/v1/messages}, the simulated inbox (FR-17), and {@code
 * POST /api/v1/messages/{id}/retry}, which makes a failed message due again (FR-18). Both exist
 * only while {@code branchbook.inbox.enabled} is true, which compose.yaml, {@code make dev-api} and
 * the tests set; otherwise their paths are a plain 404.
 */
package za.co.fynbos.branchbook.notification.api;
