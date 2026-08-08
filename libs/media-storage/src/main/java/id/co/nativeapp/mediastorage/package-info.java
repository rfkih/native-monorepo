/**
 * Shared object-storage client for binary media (ADR 0048).
 *
 * <p>The platform's object store (MinIO in every environment today) is <strong>infrastructure, not
 * a business service</strong> — talking to it is like talking to the service's own database, so the
 * hard rule "no synchronous calls between business services" is untouched. Each service owns a key
 * prefix ({@code restaurant/…}, {@code employee/…}, {@code payment/…}) the way it owns its own
 * database (rule 1's storage twin); per-service credentials are scoped to that prefix by the MinIO
 * bootstrap ({@code docker/minio/init.sh}), so cross-service access is impossible the same way
 * cross-service DB access is.
 *
 * <p>Keys are content-addressed ({@code {service}/{companyId}/{domain}/{sha256}.{ext}}, {@link
 * id.co.nativeapp.mediastorage.MediaKeys}): objects are immutable, deduplicate for free, and can be
 * served with {@code Cache-Control: immutable}. The client speaks the generic S3 API (AWS SDK v2) —
 * never a MinIO-specific one — so the store can move to any S3-compatible target with a config
 * change.
 */
package id.co.nativeapp.mediastorage;
