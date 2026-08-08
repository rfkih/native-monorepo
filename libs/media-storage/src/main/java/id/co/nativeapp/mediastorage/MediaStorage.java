package id.co.nativeapp.mediastorage;

/**
 * Object-store port for binary media (ADR 0048): put/get/delete by full object key.
 *
 * <p>Callers build keys with {@link MediaKeys} (which enforces the tenant-scoped {@code
 * {service}/{companyId}/{domain}/{sha256}.{ext}} shape) — this interface deliberately accepts the
 * finished key so the storage layer stays a dumb byte port with no tenant logic of its own; tenant
 * scoping lives in the key and in the per-service credentials.
 *
 * <p>Contract notes:
 *
 * <ul>
 *   <li>{@link #put} overwrites silently — harmless by design, because content-addressed keys mean
 *       an overwrite writes the identical bytes.
 *   <li>{@link #get} throws {@link MediaObjectNotFoundException} for a missing key; callers map it
 *       to their own 404/absent semantics.
 *   <li>{@link #delete} of a missing key is a no-op (S3 semantics). Callers doing replace-cleanup
 *       treat delete as best-effort: an orphaned content-addressed object is waste, never a bug.
 * </ul>
 */
public interface MediaStorage extends AutoCloseable {

  /** Stores {@code data} under {@code key} with the given canonical content type. */
  void put(String key, byte[] data, String contentType);

  /**
   * Loads the object stored under {@code key}.
   *
   * @throws MediaObjectNotFoundException if no object exists under {@code key}
   */
  StoredObject get(String key);

  /** Deletes the object under {@code key}; deleting a missing key is a no-op (S3 semantics). */
  void delete(String key);

  /** Releases the underlying client; the auto-configuration wires this as the bean destroy hook. */
  @Override
  void close();

  /**
   * A loaded object: its bytes and the canonical content type it was stored with.
   *
   * <p>The array is defensively copied on both construction and read (the {@code
   * ExpenseReceipt.getData()} idiom) — media are small (≤ 5 MiB caps at every upload edge), so the
   * copy is cheap insurance against aliasing.
   */
  record StoredObject(byte[] data, String contentType) {
    public StoredObject {
      data = data.clone();
    }

    @Override
    public byte[] data() {
      return data.clone();
    }
  }
}
