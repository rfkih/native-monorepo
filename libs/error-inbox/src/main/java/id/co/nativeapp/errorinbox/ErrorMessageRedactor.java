package id.co.nativeapp.errorinbox;

/**
 * Masks PII from exception messages before they are stored in {@code error_log} (HR-6 / ADR 0005).
 *
 * <p>Two operations are exposed:
 *
 * <ul>
 *   <li>{@link #redact(String)} — replaces email addresses and digit runs of length ≥ 10 (NIK /
 *       bank account / long IDs) with {@code ***}, then truncates to 2000 chars. Safe to call with
 *       {@code null} (returns "").
 *   <li>{@link #fingerprintNormalize(String)} — redacts, lowercases, collapses ALL digit runs
 *       (including short ones) to {@code #}, and collapses whitespace to single spaces. Used to
 *       produce the dedup fingerprint so near-identical messages (differing only in embedded
 *       numbers or whitespace variation) map to the same fingerprint hash.
 * </ul>
 *
 * <p>This class is pure (no external I/O) and stateless; it is safe to use from any thread.
 * Registered as a bean by {@link ErrorInboxAutoConfiguration}.
 */
public class ErrorMessageRedactor {

  /** Maximum stored length for a redacted message. */
  private static final int MAX_LENGTH = 2000;

  /**
   * Regex for an email address: {@code word@word.tld} pattern. Deliberately simple — covers {@code
   * user@example.com} and {@code cashier-a@example.co.id} without pulling in a full RFC-5321 parser
   * (we are masking, not validating).
   */
  private static final String EMAIL_PATTERN = "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}";

  /**
   * A long numeric identifier of ≥ 10 digits — covers NIK (16-digit national ID), bank account
   * numbers, phone numbers, and any other long numeric identifier. Single space / hyphen separators
   * <em>inside</em> the run are tolerated and consumed, so a formatted account ({@code 1234 5678
   * 9012}) or phone ({@code 0812-3456-7890}) is masked as a whole — not just contiguous digit runs.
   * A "short" number (e.g. an amount {@code 12345}) is NOT masked. Erring toward masking is the
   * safe direction for a non-RLS ops table whose sole PII mitigation is redaction.
   */
  private static final String LONG_DIGIT_PATTERN = "\\d(?:[ -]?\\d){9,}";

  /**
   * Any digit run of any length — used only for {@link #fingerprintNormalize(String)} where even
   * short numbers are collapsed so near-identical messages share a fingerprint.
   */
  private static final String ANY_DIGIT_PATTERN = "\\d+";

  /**
   * Redacts PII from {@code message} and truncates to {@value #MAX_LENGTH} characters.
   *
   * <ul>
   *   <li>Email addresses → {@code ***@***}
   *   <li>Digit runs of length ≥ 10 → {@code ***}
   *   <li>Result is truncated to {@value #MAX_LENGTH} chars.
   * </ul>
   *
   * @param message the raw exception message; may be {@code null}
   * @return the redacted, truncated message — never {@code null}
   */
  public String redact(String message) {
    if (message == null) {
      return "";
    }
    String redacted =
        message.replaceAll(EMAIL_PATTERN, "***@***").replaceAll(LONG_DIGIT_PATTERN, "***");
    if (redacted.length() > MAX_LENGTH) {
      redacted = redacted.substring(0, MAX_LENGTH);
    }
    return redacted;
  }

  /**
   * Produces a normalized form of {@code message} for fingerprint deduplication.
   *
   * <p>Applies {@link #redact(String)}, then:
   *
   * <ol>
   *   <li>Lowercases the result.
   *   <li>Collapses ALL digit runs (including short ones) to {@code #} — so {@code "order 42"} and
   *       {@code "order 99"} both normalize to {@code "order #"}.
   *   <li>Collapses whitespace (spaces, tabs, newlines) to a single space and trims.
   * </ol>
   *
   * @param message the raw exception message; may be {@code null}
   * @return the normalized form — never {@code null}
   */
  public String fingerprintNormalize(String message) {
    String redacted = redact(message);
    return redacted.toLowerCase().replaceAll(ANY_DIGIT_PATTERN, "#").replaceAll("\\s+", " ").trim();
  }
}
