package id.co.nativeapp.org.company.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Country-driven company defaults (ADR 0025): the platform derives a new company's base currency
 * from its country, Odoo-style — the public signup asks WHERE the company operates, never which
 * currency to keep books in.
 *
 * <p>Domain policy, not service logic: a pure, dependency-free whitelist exactly like {@link
 * Vertical}, consumed by the signup orchestration (derive-before-create, so an invalid country can
 * never leave a compensable Keycloak user behind) and by {@link Company}'s constructor (validate
 * the persisted code). The derivation table is deliberately minimal — the platform's supported base
 * currencies are still only {@code IDR} and {@code USD} (the finance stack consolidates nothing
 * else), so Indonesia keeps IDR books and every other country keeps USD books. Widening the map (or
 * the currency whitelist) is a deliberate platform decision, not a client concern.
 */
public final class CountryDefaults {

  /** All ISO 3166-1 alpha-2 codes the JDK knows — the single source of country validity. */
  private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

  private CountryDefaults() {}

  /**
   * Validates and normalizes an ISO 3166-1 alpha-2 country code (strip + upper-case).
   *
   * @param country the raw country code (e.g. {@code "ID"}, {@code "us"})
   * @return the normalized two-letter code
   * @throws IllegalArgumentException if the code is not a real ISO 3166-1 alpha-2 country (mapped
   *     to a clean {@code 400} by the shared exception handler)
   */
  public static String requireValidCountry(String country) {
    Objects.requireNonNull(country, "country");
    String code = country.strip().toUpperCase(Locale.ROOT);
    if (!ISO_COUNTRIES.contains(code)) {
      throw new IllegalArgumentException("unknown country code: " + code);
    }
    return code;
  }

  /**
   * The base (functional) currency a company in the given country keeps its books in: {@code ID →
   * IDR}, every other country {@code → USD}. The result is always inside the platform's supported
   * currency set.
   *
   * @param isoCountry a NORMALIZED country code (call {@link #requireValidCountry} first)
   * @return the ISO-4217 base currency code
   */
  public static String baseCurrencyFor(String isoCountry) {
    return "ID".equals(isoCountry) ? "IDR" : "USD";
  }
}
