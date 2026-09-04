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

  // ---------------------------------------------------------------------------
  // Language policy (ADR 0059): English-first, Indonesian only in Indonesia
  // ---------------------------------------------------------------------------

  @Test
  void englishIsAllowedForEveryCountry() {
    assertThat(CountryDefaults.requireLanguageForCountry("ID", "en")).isEqualTo("en");
    assertThat(CountryDefaults.requireLanguageForCountry("US", "en")).isEqualTo("en");
    assertThat(CountryDefaults.requireLanguageForCountry("GB", "en")).isEqualTo("en");
  }

  @Test
  void indonesianIsAllowedOnlyForIndonesia() {
    assertThat(CountryDefaults.requireLanguageForCountry("ID", "id")).isEqualTo("id");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CountryDefaults.requireLanguageForCountry("US", "id"))
        .withMessageContaining("Indonesia");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CountryDefaults.requireLanguageForCountry("SG", "id"));
  }

  @Test
  void languageIsNormalizedToLowerCase() {
    assertThat(CountryDefaults.requireLanguageForCountry("ID", " ID ")).isEqualTo("id");
    assertThat(CountryDefaults.requireLanguageForCountry("US", "EN")).isEqualTo("en");
  }

  @Test
  void anUnsupportedLanguageIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CountryDefaults.requireLanguageForCountry("ID", "fr"))
        .withMessageContaining("unsupported language");
  }

  @Test
  void nullLanguageIsRejected() {
    assertThatNullPointerException()
        .isThrownBy(() -> CountryDefaults.requireLanguageForCountry("ID", null));
  }
}
