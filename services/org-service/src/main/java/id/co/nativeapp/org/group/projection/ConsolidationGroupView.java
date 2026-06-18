package id.co.nativeapp.org.group.projection;

import java.util.UUID;

/**
 * Read projection over the {@code consolidation_group} row — only the columns a consumer of the
 * list-all-groups read path needs, never the {@code Auditable} bookkeeping.
 *
 * <p>Backs the native read query on {@link
 * id.co.nativeapp.org.group.repository.ConsolidationGroupRepository} ({@code findAllViews}).
 * Snake_case native-query aliases map to these accessors via Spring Data's projection-interface
 * convention (CLAUDE.md "native-query aliases snake_case; map via projection interfaces"), so a
 * read path fetches a narrow column set instead of {@code SELECT *} of the full entity. Lives in
 * its own {@code projection} package — a read model is neither the write-side {@code domain} entity
 * nor a request/response {@code dto}.
 *
 * <p>{@code reporting_currency} is a plain {@code CHAR(3)} column with no {@code
 * AttributeConverter}; the native query returns the raw padded value and the service layer strips
 * it (exactly as {@link id.co.nativeapp.org.group.domain.ConsolidationGroup#getReportingCurrency()}
 * does for the entity path).
 */
public interface ConsolidationGroupView {

  UUID getId();

  UUID getLeadCompanyId();

  String getName();

  /** The ISO-4217 reporting currency code — CHAR(3), may be space-padded; strip before use. */
  String getReportingCurrency();
}
