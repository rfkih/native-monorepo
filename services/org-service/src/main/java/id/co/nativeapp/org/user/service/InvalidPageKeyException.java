package id.co.nativeapp.org.user.service;

/**
 * A page grant referenced a key outside the whitelist ({@code pos | kitchen | menu | me}) — mapped
 * to {@code 400} with a stable RFC-7807 type. Rejected before any write (all-or-nothing).
 */
public class InvalidPageKeyException extends RuntimeException {

  public InvalidPageKeyException(String pageKey) {
    super("Unknown page key: " + pageKey);
  }
}
