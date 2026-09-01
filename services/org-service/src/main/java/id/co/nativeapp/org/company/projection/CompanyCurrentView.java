package id.co.nativeapp.org.company.projection;

import java.util.UUID;

/**
 * Read projection for the {@code GET /api/v1/companies/current} endpoint — the company row joined
 * with its first business (the earliest-created {@code BUSINESS_UNIT} org unit with {@code
 * parent_id IS NULL}).
 *
 * <p>Extends the column set of {@link CompanyView} with {@code first_business_id}, which is
 * resolved in the same native query that fetches the company row (a single join, no second
 * round-trip). The query lives in {@link
 * id.co.nativeapp.org.company.repository.CompanyRepository#findCurrentView()}.
 *
 * <p>Follows the native-query + projection convention (CLAUDE.md §3.3): snake_case column aliases
 * map to the camelCase getter names via Spring Data's projection-interface convention. Lives in the
 * feature's {@code projection} sub-package, not in {@code dto}.
 */
public interface CompanyCurrentView {

  UUID getId();

  String getName();

  /** The ISO-4217 base currency code — CHAR(3), may be space-padded; strip before use. */
  String getBaseCurrency();

  String getDefaultLanguage();

  UUID getLegalEmployerId();

  /** The company's plan tier ({@code FREE} | {@code FULL}) — ADR 0044. */
  String getPlanTier();

  /** The immutable 6-char login-namespace code (ADR 0054). */
  String getCompanyCode();

  /**
   * The id of the first business: the earliest-created {@code BUSINESS_UNIT} org unit with {@code
   * parent_id IS NULL} for this company, matching the selection the create-company flow inserts.
   */
  /** The company's immutable business vertical (ADR 0070) — lowercase module-key value. */
  String getVertical();

  UUID getFirstBusinessId();
}
