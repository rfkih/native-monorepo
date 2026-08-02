package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.config.PiiCipher;
import id.co.nativeapp.employee.payroll.domain.MoneyPiiConverter;
import id.co.nativeapp.employee.payroll.domain.PayComponentBearer;
import id.co.nativeapp.employee.payroll.domain.PayComponentKind;
import id.co.nativeapp.employee.payroll.domain.PayslipLine;
import id.co.nativeapp.employee.payroll.dto.PayslipLineResponse;
import id.co.nativeapp.money.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link MoneyPiiConverter} round-trip + PII masking proof (rule 6): a salary {@link Money}
 * encrypts to ciphertext that does NOT contain the plaintext digits, decrypts back to the same
 * value, and the masked payslip DTO never carries the minor amount while the authorized DTO does.
 */
class MoneyPiiConverterTest {

  // A deterministic 32-byte (AES-256) test key (the 32 ASCII bytes
  // "native-pii-test-key-0123456789ab").
  private static final String KEY = "bmF0aXZlLXBpaS10ZXN0LWtleS0wMTIzNDU2Nzg5YWI=";

  private final MoneyPiiConverter converter = new MoneyPiiConverter(PiiCipher.fromBase64Key(KEY));

  @Test
  void encryptsToCiphertextAndDecryptsBackToTheSameMoney() {
    Money salary = Money.ofMinor(20_000_000L, "IDR");
    String ciphertext = converter.convertToDatabaseColumn(salary);

    // The ciphertext does NOT contain the plaintext amount digits (rule 6).
    assertThat(ciphertext).doesNotContain("20000000").doesNotContain("IDR");
    // It round-trips back to the same value.
    assertThat(converter.convertToEntityAttribute(ciphertext)).isEqualTo(salary);
  }

  @Test
  void encryptsToCiphertextAndDecryptsBackToTheSameNegativeMoney() {
    // The December/final-month Art-17 true-up (Track P phase P3) can produce a NEGATIVE payslip
    // line (an over-withheld refund) — the converter must round-trip the sign, not just positive
    // salary figures.
    Money refund = Money.ofMinor(-62_665_700L, "IDR");
    String ciphertext = converter.convertToDatabaseColumn(refund);

    assertThat(ciphertext).doesNotContain("62665700").doesNotContain("-62665700");
    assertThat(converter.convertToEntityAttribute(ciphertext)).isEqualTo(refund);
  }

  @Test
  void randomIvMeansTwoEncryptionsOfTheSameValueDiffer() {
    Money salary = Money.ofMinor(12_345_678L, "USD");
    String a = converter.convertToDatabaseColumn(salary);
    String b = converter.convertToDatabaseColumn(salary);
    assertThat(a).isNotEqualTo(b); // never leaks value equality
    assertThat(converter.convertToEntityAttribute(a))
        .isEqualTo(converter.convertToEntityAttribute(b));
  }

  @Test
  void nullMapsToNull() {
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  void maskedPayslipLineHidesTheAmountWhileAuthorizedExposesIt() {
    PayslipLine line =
        new PayslipLine(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "BASE",
            PayComponentKind.EARNING,
            PayComponentBearer.EMPLOYEE,
            "5100-SALARY",
            Money.ofMinor(20_000_000L, "IDR"),
            Money.ofMinor(20_000_000L, "IDR"),
            null,
            false);

    PayslipLineResponse masked = PayslipLineResponse.masked(line);
    assertThat(masked.amountMinor()).isNull();
    assertThat(masked.amountMasked()).isEqualTo("***");

    PayslipLineResponse authorized = PayslipLineResponse.authorized(line);
    assertThat(authorized.amountMinor()).isEqualTo(20_000_000L);
    assertThat(authorized.currency()).isEqualTo("IDR");
  }
}
