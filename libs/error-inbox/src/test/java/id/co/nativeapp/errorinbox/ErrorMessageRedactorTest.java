package id.co.nativeapp.errorinbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** PII redaction (HR-6 / ADR 0005): emails + long digit runs masked; fingerprint normalises. */
class ErrorMessageRedactorTest {

  private final ErrorMessageRedactor redactor = new ErrorMessageRedactor();

  @Test
  void redactsEmailAddresses() {
    assertThat(redactor.redact("login failed for cashier-a@example.co.id on retry"))
        .contains("***@***")
        .doesNotContain("cashier-a@example.co.id");
  }

  @Test
  void masksLongDigitRunsIncludingSpacedAndHyphenated() {
    // 16-digit NIK, spaced bank account, hyphenated phone — all ≥10 digits → masked whole.
    assertThat(redactor.redact("nik 3201234567890123 acct 1234 5678 9012 ph 0812-3456-7890"))
        .doesNotContain("3201234567890123")
        .doesNotContain("1234 5678 9012")
        .doesNotContain("0812-3456-7890")
        .contains("***");
  }

  @Test
  void doesNotMaskShortNumbersInRedact() {
    // A short amount (5 digits) is not PII — left intact by redact().
    assertThat(redactor.redact("amount 12345 rejected")).contains("12345");
  }

  @Test
  void nullMessageRedactsToEmptyString() {
    assertThat(redactor.redact(null)).isEmpty();
  }

  @Test
  void fingerprintNormalizeCollapsesAllDigitsAndWhitespace() {
    // Two messages differing only in embedded numbers normalise to the same fingerprint input.
    String a = redactor.fingerprintNormalize("Order 42 failed   for  unit 7");
    String b = redactor.fingerprintNormalize("Order 99 failed for unit 13");
    assertThat(a).isEqualTo(b).isEqualTo("order # failed for unit #");
  }
}
