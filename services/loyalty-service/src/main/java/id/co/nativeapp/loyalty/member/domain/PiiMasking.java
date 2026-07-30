package id.co.nativeapp.loyalty.member.domain;

/**
 * Masks PII for safe display in a response/DTO (rule 6 — the plaintext PII never reaches a
 * non-authorized caller beyond what the POS lookup needs). Ported from employee-service's {@code
 * PiiMasking}: {@link #lastFour(String)} reveals only the last 4 characters behind a {@code "****"}
 * prefix — the cashier-confirmation affordance ("ends in 7890") the loyalty member lookup response
 * uses for the phone tail (see {@code member.dto.MemberResponse} class javadoc for the full PII
 * decision on what a lookup response returns).
 *
 * <p>Never returns the raw plaintext, and is null-safe.
 */
final class PiiMasking {

  private static final int VISIBLE_TAIL = 4;

  private PiiMasking() {}

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
