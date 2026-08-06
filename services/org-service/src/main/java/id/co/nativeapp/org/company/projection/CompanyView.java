package id.co.nativeapp.org.company.projection;

import java.util.UUID;

/**
 * Read projection over the {@code company} row — only the columns a {@link
 * id.co.nativeapp.org.company.dto.CompanyResponse} needs, never the {@code Auditable} bookkeeping.
 *
 * <p>Backs the native read query on {@link
 * id.co.nativeapp.org.company.repository.CompanyRepository} ({@code findAllViews}). Snake_case
 * native-query aliases map to these accessors via Spring Data's projection-interface convention
 * (CLAUDE.md "native-query aliases snake_case; map via projection interfaces"), so a read path
 * fetches a narrow column set instead of {@code SELECT *} of the full entity. Lives in its own
 * {@code projection} package — a read model is neither the write-side {@code domain} entity nor a
 * request/response {@code dto}.
 *
 * <p>{@code base_currency} is a plain {@code CHAR(3)} column with no {@code AttributeConverter};
 * the native query returns the raw padded value and the service layer strips it (exactly as {@link
 * id.co.nativeapp.org.company.domain.Company#getBaseCurrency()} does for the entity path).
 */
public interface CompanyView {

  UUID getId();

  String getName();

  /** The ISO-4217 base currency code — CHAR(3), may be space-padded; strip before use. */
  String getBaseCurrency();

  String getDefaultLanguage();

  UUID getLegalEmployerId();

  /** The company's plan tier ({@code FREE} | {@code FULL}) — ADR 0044. */
  String getPlanTier();
}
