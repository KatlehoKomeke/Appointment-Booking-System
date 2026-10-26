/**
 * Notification's HTTP API: {@code GET /api/v1/messages}, the simulated inbox (FR-17). It exists
 * only while {@code branchbook.inbox.enabled} is true, which compose.yaml, {@code make dev-api} and
 * the tests set; otherwise the path is a plain 404.
 */
package za.co.fynbos.branchbook.notification.api;
