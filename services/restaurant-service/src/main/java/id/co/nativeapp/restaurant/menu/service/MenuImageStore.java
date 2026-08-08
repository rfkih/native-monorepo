package id.co.nativeapp.restaurant.menu.service;

import id.co.nativeapp.mediastorage.ImageContentTypeValidator;
import id.co.nativeapp.mediastorage.MediaKeys;
import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.mediastorage.MediaStorageProperties;
import id.co.nativeapp.mediastorage.Sha256;
import id.co.nativeapp.restaurant.menu.domain.InvalidMenuImageException;
import java.util.Base64;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Menu-image gateway to the object store (ADR 0048): converts an inline base64 data URL (the shape
 * the console has always uploaded — {@code image.ts} canvas output) into a content-addressed stored
 * object, and resolves stored keys to the public {@code /api/media/…} URL for responses.
 *
 * <p>Convert-on-write keeps the API contract unchanged: clients still send {@code imageUrl} (data
 * URL or external URL) on create/PATCH; the writer intercepts data URLs here BEFORE they reach the
 * aggregate, so the DB only ever gains an object key. External HTTP(S) URLs pass through untouched
 * (the documented legacy affordance). The S3 put happens inside the write transaction — a rollback
 * orphans one content-addressed object, which is harmless by design (same bytes, same key, next
 * attempt overwrites it).
 */
@Component
public class MenuImageStore {

  /**
   * Cap on the DECODED image, comfortably above the console's 1.5 MB client-side resize output and
   * below the 3 MB request-string {@code @Size} cap (which bounds the base64 form).
   */
  static final int MAX_DECODED_BYTES = 2 * 1024 * 1024;

  private static final String DOMAIN = "menu";
  private static final String BASE64_MARKER = ";base64,";

  private final MediaStorage storage;
  private final MediaStorageProperties properties;

  public MenuImageStore(MediaStorage storage, MediaStorageProperties properties) {
    this.storage = storage;
    this.properties = properties;
  }

  /** Whether an incoming {@code imageUrl} value is an inline data URL (vs an external URL). */
  public static boolean isDataUrl(@Nullable String value) {
    return value != null && value.startsWith("data:");
  }

  /**
   * Decodes, validates (magic bytes vs the data URL's declared MIME — the untrusted-header rule),
   * and stores the image; returns the content-addressed object key to attach to the aggregate.
   *
   * @param companyId the bound tenant (the key's tenant segment — from {@code TenantContext}, never
   *     the request)
   * @param dataUrl the inline {@code data:image/…;base64,…} payload
   * @throws InvalidMenuImageException malformed/empty/oversized payload (→ 422)
   * @throws id.co.nativeapp.mediastorage.UnsupportedImageTypeException bytes match no whitelisted
   *     image signature or contradict the declared MIME (→ 422)
   */
  public String storeDataUrl(String companyId, String dataUrl) {
    int marker = dataUrl.indexOf(BASE64_MARKER);
    if (marker < 0) {
      throw new InvalidMenuImageException("image data URL is not base64-encoded");
    }
    String declaredMime = dataUrl.substring("data:".length(), marker);
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(dataUrl.substring(marker + BASE64_MARKER.length()));
    } catch (IllegalArgumentException e) {
      throw new InvalidMenuImageException("image data URL carries a malformed base64 payload");
    }
    if (bytes.length == 0) {
      throw new InvalidMenuImageException("image payload is empty");
    }
    if (bytes.length > MAX_DECODED_BYTES) {
      throw new InvalidMenuImageException(
          "decoded image is " + bytes.length + " bytes; the cap is " + MAX_DECODED_BYTES);
    }
    String canonicalType =
        ImageContentTypeValidator.validate(declaredMime.isBlank() ? null : declaredMime, bytes);
    String key =
        MediaKeys.imageKey(
            properties.servicePrefix(), companyId, DOMAIN, Sha256.hex(bytes), canonicalType);
    storage.put(key, bytes, canonicalType);
    return key;
  }

  /**
   * The response-facing image URL: a stored key resolves to its public {@code /api/media/…} URL;
   * otherwise the legacy value (data URL or external URL) passes through; {@code null} = no image.
   */
  @Nullable public String resolve(@Nullable String imageKey, @Nullable String legacyImageUrl) {
    return imageKey != null ? properties.publicUrlFor(imageKey) : legacyImageUrl;
  }
}
