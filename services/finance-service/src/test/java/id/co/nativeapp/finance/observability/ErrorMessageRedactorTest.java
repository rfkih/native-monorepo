package id.co.nativeapp.finance.observability;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.observability.service.ErrorMessageRedactor;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ErrorMessageRedactor} — no Spring context required.
 *
 * <p>Verifies: email masking, NIK/bank-account masking (digits ≥ 10), short-number preservation,
 * 2000-char truncation, and the {@code fingerprintNormalize} dedup collapsing.
 */
class ErrorMessageRedactorTest {

  private final ErrorMessageRedactor redactor = new ErrorMessageRedactor();

  // ------------------------------------------------------------------ redact()

  @Test
  void emailAddressIsMasked() {
    String result = redactor.redact("user failed login: cashier-a@example.co.id");
    assertThat(result).doesNotContain("cashier-a@example.co.id").contains("***@***");
  }

  @Test
  void multipleEmailsAreMasked() {
    String result = redactor.redact("from user@foo.com to admin@bar.org: error");
    assertThat(result).doesNotContain("user@foo.com").doesNotContain("admin@bar.org");
  }

  @Test
  void sixteenDigitNikIsMasked() {
    // A 16-digit NIK — must be masked (length >= 10)
    String result = redactor.redact("employee NIK 3201234567890001 not found");
    assertThat(result).doesNotContain("3201234567890001").contains("***");
  }

  @Test
  void longBankAccountIsMasked() {
    // A 10-digit bank account — minimum threshold for masking
    String result = redactor.redact("bank account 1234567890 invalid");
    assertThat(result).doesNotContain("1234567890").contains("***");
  }

  @Test
  void spaceSeparatedBankAccountIsMasked() {
    // A bank account formatted with spaces (12 digits across groups) must be masked as a whole —
    // contiguous-only masking would have leaked it.
    String result = redactor.redact("transfer to 1234 5678 9012 was rejected");
    assertThat(result).doesNotContain("1234 5678 9012").doesNotContain("5678").contains("***");
  }

  @Test
  void hyphenSeparatedPhoneIsMasked() {
    // A phone number formatted with hyphens (12 digits) must be masked as a whole.
    String result = redactor.redact("sms to 0812-3456-7890 failed");
    assertThat(result).doesNotContain("0812-3456-7890").doesNotContain("3456").contains("***");
  }

  @Test
  void shortNumberIsNotMasked() {
    // A 5-digit amount like "12345" MUST NOT be masked (less than 10 digits)
    String result = redactor.redact("amount 12345 is invalid");
    assertThat(result).contains("12345");
  }

  @Test
  void nineDigitNumberIsNotMasked() {
    // 9 digits — just below the threshold
    String result = redactor.redact("order 123456789 not found");
    assertThat(result).contains("123456789");
  }

  @Test
  void nullMessageReturnsEmpty() {
    assertThat(redactor.redact(null)).isEmpty();
  }

  @Test
  void messageTruncatedAt2000Chars() {
    String longMessage = "x".repeat(3000);
    String result = redactor.redact(longMessage);
    assertThat(result).hasSize(2000);
  }

  @Test
  void messageExactly2000CharsIsNotTruncated() {
    String message = "a".repeat(2000);
    assertThat(redactor.redact(message)).hasSize(2000);
  }

  // --------------------------------------------------------- fingerprintNormalize()

  @Test
  void fingerprintNormalizeLowercases() {
    String norm = redactor.fingerprintNormalize("ERROR: Cannot find Order ABC");
    assertThat(norm).isEqualTo(norm.toLowerCase());
  }

  @Test
  void fingerprintNormalizeCollapsesAllDigitRunsToHash() {
    String norm = redactor.fingerprintNormalize("order 42 failed with code 99");
    assertThat(norm).contains("#").doesNotContain("42").doesNotContain("99");
  }

  @Test
  void fingerprintNormalizeTwoMessagesDifferingOnlyInNumbersShareForm() {
    String norm1 = redactor.fingerprintNormalize("Timeout after 3000ms for tenant 11111");
    String norm2 = redactor.fingerprintNormalize("Timeout after 5000ms for tenant 22222");
    assertThat(norm1).isEqualTo(norm2);
  }

  @Test
  void fingerprintNormalizeCollapsesWhitespace() {
    String norm = redactor.fingerprintNormalize("error   occurred    here");
    assertThat(norm).isEqualTo("error occurred here");
  }

  @Test
  void fingerprintNormalizeNullReturnsEmpty() {
    assertThat(redactor.fingerprintNormalize(null)).isEmpty();
  }

  @Test
  void fingerprintNormalizeMasksEmailBeforeCollapsing() {
    // email is masked to ***@*** then digits in *** are not present, lowercased
    String norm = redactor.fingerprintNormalize("Login failed for user@example.com");
    assertThat(norm).doesNotContain("user@example.com");
  }
}
