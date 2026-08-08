package id.co.nativeapp.mediastorage;

import java.util.regex.Pattern;

/**
 * Builder for the fleet's ONE object-key shape (ADR 0048):
 *
 * <pre>{service}/{companyId}/{domain}/{sha256}.{ext}</pre>
 *
 * <p>e.g. {@code restaurant/0198c1c2-…/menu/9f8a3b….jpg}. The shape is load-bearing three ways:
 *
 * <ul>
 *   <li><strong>{service}</strong> — the per-service prefix is the storage twin of
 *       database-per-service (rule 1): each service's MinIO credentials are policy-scoped to its
 *       own prefix, so cross-service access is impossible at the store.
 *   <li><strong>{companyId}</strong> — tenant isolation at the storage layer; a company offboarding
 *       is a prefix delete.
 *   <li><strong>{sha256}.{ext}</strong> — content addressing: objects are immutable (safe for
 *       {@code Cache-Control: immutable} and CDN fronting later), deduplicate for free, and an
 *       interrupted upload can simply be retried.
 * </ul>
 *
 * <p>Every segment is validated against a strict character whitelist — a key is built from values
 * that ultimately arrive over the wire (tenant ids, content types), and no combination of them may
 * ever produce {@code ..}, a slash, or any other path-shaped surprise inside a segment.
 */
public final class MediaKeys {

  /** Fixed lowercase segments the code itself supplies (service prefix, domain). */
  private static final Pattern SEGMENT = Pattern.compile("[a-z0-9][a-z0-9-]*");

  /** Company ids are UUID strings today (VARCHAR(64) in every tenant column); no separators. */
  private static final Pattern COMPANY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  private MediaKeys() {}

  /**
   * Builds the canonical image key.
   *
   * @param servicePrefix the owning service's fixed prefix ({@code restaurant}, {@code employee},
   *     {@code payment}) — from {@code native.media.service-prefix}, never a request value
   * @param companyId the tenant (the same value RLS scopes on)
   * @param domain the media family within the service ({@code menu}, {@code receipt}, {@code qr})
   * @param sha256Hex lowercase hex SHA-256 of the content ({@link Sha256#hex})
   * @param canonicalContentType a {@code CONTENT_TYPE_*} constant from {@link
   *     ImageContentTypeValidator} (validated, never the raw declared header)
   * @throws IllegalArgumentException if any segment fails its whitelist — a programming or
   *     tampering error, never a normal outcome
   */
  public static String imageKey(
      String servicePrefix,
      String companyId,
      String domain,
      String sha256Hex,
      String canonicalContentType) {
    require(SEGMENT, servicePrefix, "servicePrefix");
    require(COMPANY_ID, companyId, "companyId");
    require(SEGMENT, domain, "domain");
    require(SHA256_HEX, sha256Hex, "sha256Hex");
    return servicePrefix
        + "/"
        + companyId
        + "/"
        + domain
        + "/"
        + sha256Hex
        + "."
        + extensionFor(canonicalContentType);
  }

  /** Maps a canonical image content type to its key extension. */
  public static String extensionFor(String canonicalContentType) {
    return switch (canonicalContentType) {
      case ImageContentTypeValidator.CONTENT_TYPE_JPEG -> "jpg";
      case ImageContentTypeValidator.CONTENT_TYPE_PNG -> "png";
      case ImageContentTypeValidator.CONTENT_TYPE_WEBP -> "webp";
      default ->
          throw new IllegalArgumentException(
              "not a canonical image content type: " + canonicalContentType);
    };
  }

  private static void require(Pattern pattern, String value, String name) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " is not a valid media key segment: " + value);
    }
  }
}
