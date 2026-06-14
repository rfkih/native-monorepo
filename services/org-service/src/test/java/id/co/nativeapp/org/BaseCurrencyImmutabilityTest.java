package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.org.company.Company;
import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Acceptance (b) — base_currency is immutable (write-once), proven without a Spring context.
 *
 * <p>Three structural guarantees, all independent of the database:
 *
 * <ul>
 *   <li>the {@link Company} aggregate exposes NO public method that reassigns {@code baseCurrency}
 *       (no {@code setBaseCurrency}, no other setter that takes the currency);
 *   <li>the {@code baseCurrency} field is mapped {@code @Column(updatable = false)}, so even a
 *       Hibernate dirty-check / flush cannot rewrite the column after insert; and
 *   <li>the only way to set it is the validating constructor, which rejects a non-ISO-4217 code.
 * </ul>
 *
 * <p>A persistence-level no-op proof (the stored value is unchanged after re-saving the entity)
 * lives in {@link BaseCurrencyImmutabilityPersistenceTest}. Together they show there is no path —
 * in code or via the ORM — to change a company's base currency after creation (CLAUDE.md "Settings
 * live at creation").
 */
class BaseCurrencyImmutabilityTest {

  @Test
  void companyExposesNoSetterThatReassignsBaseCurrency() {
    boolean hasMutator =
        Arrays.stream(Company.class.getMethods())
            .anyMatch(
                m ->
                    m.getName().toLowerCase(java.util.Locale.ROOT).contains("basecurrency")
                        && m.getParameterCount() == 1
                        && !m.getName().startsWith("get"));
    assertThat(hasMutator)
        .as("Company must expose no public method that reassigns base_currency (write-once)")
        .isFalse();
  }

  @Test
  void baseCurrencyColumnIsMappedNonUpdatable() throws Exception {
    Field field = Company.class.getDeclaredField("baseCurrency");
    Column column = field.getAnnotation(Column.class);
    assertThat(column).isNotNull();
    assertThat(column.updatable())
        .as("base_currency must be @Column(updatable=false) so a flush cannot rewrite it")
        .isFalse();
  }

  @Test
  void constructorValidatesBaseCurrencyIsRealIso4217() {
    UUID id = UUID.randomUUID();
    // A valid code is accepted and normalized.
    Company company = new Company(id, "Acme", "usd", "en", id);
    assertThat(company.getBaseCurrency()).isEqualTo("USD");

    // An unknown code is rejected (mapped to a 400 by the API exception handler).
    assertThatThrownBy(() -> new Company(UUID.randomUUID(), "Bad", "ZZZ", "en", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
