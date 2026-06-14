package id.co.nativeapp.employee.payroll;

import id.co.nativeapp.employee.config.PiiCipher;
import id.co.nativeapp.money.Money;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * JPA {@link AttributeConverter} that makes a {@link Money} field <strong>PII ciphertext at
 * rest</strong> (rule 6 — salary/compensation is PII). It is applied explicitly via
 * {@code @Convert(converter = MoneyPiiConverter.class)} on the salary-bearing money fields ({@code
 * comp_package.base_pay_enc}, {@code payslip_line.amount_enc}/{@code calc_basis_enc}, {@code
 * earning_rule.fixed_amount_enc}). The application works entirely in {@link Money}; the database
 * row only ever holds the AES-256-GCM ciphertext in a {@code TEXT} column.
 *
 * <p><strong>Canonical serialization.</strong> A {@link Money} is encrypted by serializing it to
 * its canonical string form {@code "<amountMinor> <currencyCode>"} — exactly what {@link
 * Money#toString()} yields — and encrypting that with the shared {@link PiiCipher} (the same
 * AES-256-GCM cipher, random IV per value, key from {@code NATIVE_PII_KEY}, that the
 * NIK/bank-account converter uses). On read the ciphertext is decrypted and {@link Money}
 * reconstructed via {@link Money#ofMinor(long, String)}, which re-validates the ISO-4217 currency.
 *
 * <p>This is why salary amounts are NOT stored in queryable {@code amount_minor}/{@code currency}
 * columns and are NOT sortable/aggregatable by value — that is the intended, desired property for
 * PII (a known, accepted trade-off; run-level totals are computed in-memory and stored as the run
 * aggregate). The converter never logs the plaintext or the ciphertext.
 *
 * <p><strong>Spring-managed.</strong> A {@code @Component} converter is instantiated by Spring with
 * its {@link PiiCipher} dependency injected (Hibernate's {@code BeanContainer} is backed by the
 * application context), so the externally-sourced key reaches the converter without static state.
 */
@Component
@Converter
public class MoneyPiiConverter implements AttributeConverter<Money, String> {

  private final PiiCipher cipher;

  public MoneyPiiConverter(PiiCipher cipher) {
    this.cipher = cipher;
  }

  /** Encrypts the {@link Money} (as its canonical {@code "<minor> <CUR>"} string) to ciphertext. */
  @Override
  public String convertToDatabaseColumn(Money attribute) {
    if (attribute == null) {
      return null;
    }
    return cipher.encryptToString(attribute.toString());
  }

  /** Decrypts the stored ciphertext and reconstructs the {@link Money} value. */
  @Override
  public Money convertToEntityAttribute(String dbData) {
    if (dbData == null) {
      return null;
    }
    return parse(cipher.decryptFromString(dbData));
  }

  /**
   * Parses the canonical {@code "<amountMinor> <currencyCode>"} form back into a {@link Money}.
   * Package-private so a round-trip test can exercise it; never given untrusted external input (the
   * only source is our own {@link Money#toString()}).
   */
  static Money parse(String canonical) {
    int space = canonical.lastIndexOf(' ');
    if (space < 0) {
      throw new IllegalArgumentException("Not a canonical Money string");
    }
    long minor = Long.parseLong(canonical.substring(0, space).strip());
    String code = canonical.substring(space + 1).strip();
    return Money.ofMinor(minor, code);
  }
}
