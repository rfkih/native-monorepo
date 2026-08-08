package id.co.nativeapp.mediastorage;

/**
 * Thrown by {@link MediaStorage#get} when no object exists under the requested key. Callers map
 * this to their own absent semantics (a 404, a null image, a skipped migration row) — it is a
 * normal domain outcome, not an infrastructure failure.
 */
public class MediaObjectNotFoundException extends RuntimeException {

  private final String key;

  public MediaObjectNotFoundException(String key, Throwable cause) {
    super("no media object stored under key: " + key, cause);
    this.key = key;
  }

  public String getKey() {
    return key;
  }
}
