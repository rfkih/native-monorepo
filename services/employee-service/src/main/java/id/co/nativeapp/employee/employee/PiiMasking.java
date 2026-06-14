package id.co.nativeapp.employee.employee;

/**
 * Masks PII for safe display in a response/DTO (rule 6 — the plaintext PII never reaches a
 * non-authorized caller). Two policies:
 *
 * <ul>
 *   <li>{@link #redact(String)} — fully redacts a value to {@code "***REDACTED***"} (used for the
 *       NIK, the national id, which is too sensitive to reveal even partially);
 *   <li>{@link #lastFour(String)} — reveals only the last 4 characters behind {@code "****"} (used
 *       for the bank account, the common "ending in 6789" affordance).
 * </ul>
 *
 * <p>Neither method returns the raw plaintext, and both are null-safe.
 */
final class PiiMasking {

  private static final String REDACTED = "***REDACTED***";
  private static final int VISIBLE_TAIL = 4;

  private PiiMasking() {}

  /** Fully redacts a value; {@code null} stays {@code null}. */
  static String redact(String value) {
    return value == null ? null : REDACTED;
  }

  /**
   * Reveals only the last {@value #VISIBLE_TAIL} characters behind a {@code "****"} prefix. A value
   * of {@value #VISIBLE_TAIL} characters or fewer is fully masked (no tail revealed) so a short
   * value cannot be shown in the clear. {@code null} stays {@code null}.
   */
  static String lastFour(String value) {
    if (value == null) {
      return null;
    }
    String stripped = value.strip();
    if (stripped.length() <= VISIBLE_TAIL) {
      return "****";
    }
    return "****" + stripped.substring(stripped.length() - VISIBLE_TAIL);
  }
}
