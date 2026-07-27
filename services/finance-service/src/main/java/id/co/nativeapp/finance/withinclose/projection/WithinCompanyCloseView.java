package id.co.nativeapp.finance.withinclose.projection;

/**
 * Read projection over the {@code within_company_close} row — only the columns the historical
 * closes list needs. No {@code Auditable} bookkeeping; no entity on the wire.
 *
 * <p>Backs the native read query on {@link
 * id.co.nativeapp.finance.withinclose.repository.WithinCompanyCloseRepository} ({@code
 * findAllViews}). Snake_case native-query aliases map to these accessors via Spring Data's
 * projection-interface convention (CLAUDE.md "native-query aliases snake_case; map via projection
 * interfaces"), so the read path fetches a narrow column set instead of {@code SELECT *} of the
 * full entity. Lives in its own {@code projection} package — a read model is neither the write-side
 * {@code domain} entity nor a request/response {@code dto}.
 */
public interface WithinCompanyCloseView {

  /**
   * Deliberately returns {@code String} over a UUID column; PostgreSQL casts uuid→text
   * transparently for the projection, and {@code CloseHistoryResponse.closeId} is a String on the
   * JSON surface.
   */
  String getId();

  String getPeriod();

  /** The company's base ISO-4217 currency — CHAR(3), may be space-padded; strip before use. */
  String getBaseCurrency();

  boolean isReconciled();

  boolean isUsesIllustrativeRules();
}
