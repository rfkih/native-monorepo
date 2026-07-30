package id.co.nativeapp.loyalty.member.domain;

import java.util.Objects;

/**
 * Normalizes a member phone number to a single canonical form before it is hashed ({@code
 * PhoneHasher}) or encrypted ({@code PiiCipher}) — two different spellings of the SAME number (with
 * or without spaces/dashes/parentheses) must normalize identically, or the {@code phone_hash}
 * exact-match lookup would silently miss an existing member and enroll a duplicate.
 *
 * <p><strong>The normalization scheme (documented, per the Phase-4 task).</strong> Strip every
 * character that is not a digit or a LEADING {@code '+'} (spaces, hyphens, parentheses, dots — any
 * punctuation a cashier might type). A {@code '+'} is preserved ONLY as the very first character
 * (an international prefix); any {@code '+'} elsewhere in the input is dropped along with the other
 * punctuation. This is deliberately a SIMPLE, deterministic scheme — it does NOT attempt full E.164
 * normalization (no country-code inference for a bare local number, e.g. {@code "0812-3456-7890"}
 * normalizes to {@code "081234567890"}, NOT {@code "+6281234567890"}): the same tenant's cashiers
 * are expected to enter numbers consistently, and a locale-aware libphonenumber dependency is out
 * of scope for this phase. A future phase MAY replace this with true E.164 normalization as a data
 * migration, not a code-only change (existing {@code phone_hash} values would need re-hashing).
 *
 * <p>The result is never PII-logged; only the normalized digits are handed to the hasher/cipher.
 */
public final class PhoneNormalizer {

  private PhoneNormalizer() {}

  /**
   * Normalizes a raw phone number: strips whitespace/dashes/parentheses/dots, keeping only digits
   * and an optional leading {@code '+'}.
   *
   * @param raw the raw phone number as entered at the till
   * @return the normalized phone number (digits, optionally leading with {@code '+'})
   * @throws IllegalArgumentException if the input is blank or normalizes to an empty/too-short
   *     value (fewer than 6 digits — not a plausible phone number)
   */
  public static String normalize(String raw) {
    Objects.requireNonNull(raw, "raw");
    String trimmed = raw.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("phone must not be blank");
    }
    boolean leadingPlus = trimmed.startsWith("+");
    StringBuilder digits = new StringBuilder();
    for (char c : trimmed.toCharArray()) {
      if (Character.isDigit(c)) {
        digits.append(c);
      }
    }
    if (digits.length() < 6) {
      throw new IllegalArgumentException("phone does not contain a plausible number of digits");
    }
    return leadingPlus ? "+" + digits : digits.toString();
  }
}
