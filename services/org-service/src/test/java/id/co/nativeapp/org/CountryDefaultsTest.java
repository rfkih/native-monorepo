package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import id.co.nativeapp.org.company.domain.CountryDefaults;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the country → base-currency derivation policy (ADR 0025): Indonesia keeps IDR
 * books, every other country keeps USD books; only real ISO 3166-1 alpha-2 codes are accepted.
 */
class CountryDefaultsTest {

  @Test
  void indonesiaDerivesIdr() {
    assertThat(CountryDefaults.baseCurrencyFor("ID")).isEqualTo("IDR");
  }

  @Test
  void everyOtherCountryDerivesUsd() {
    assertThat(CountryDefaults.baseCurrencyFor("US")).isEqualTo("USD");
    assertThat(CountryDefaults.baseCurrencyFor("GB")).isEqualTo("USD");
    assertThat(CountryDefaults.baseCurrencyFor("SG")).isEqualTo("USD");
  }

  @Test
  void validationNormalizesCaseAndWhitespace() {
    assertThat(CountryDefaults.requireValidCountry(" id ")).isEqualTo("ID");
    assertThat(CountryDefaults.requireValidCountry("us")).isEqualTo("US");
  }

  @Test
  void anUnknownCodeIsRejected() {
    // "XX" and "ZZ" are structurally valid alpha-2 strings but not ISO 3166-1 countries.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CountryDefaults.requireValidCountry("XX"))
        .withMessageContaining("unknown country code");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CountryDefaults.requireValidCountry("ZZ"));
  }

  @Test
  void nullIsRejected() {
    assertThatNullPointerException().isThrownBy(() -> CountryDefaults.requireValidCountry(null));
  }
}
