package id.co.nativeapp.finance.companyexpense.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for the company-expense list (newest first): only the columns the list renders,
 * never {@code SELECT *} (CLAUDE.md). snake_case aliases map to these camelCase getters. Reached
 * only from the service + repository layers (ArchUnit projection rule).
 */
public interface CompanyExpenseSummaryView {

  UUID getId();

  String getExpenseNo();

  String getKind();

  UUID getBusinessId();

  String getGlHint();

  String getDescription();

  long getAmountMinor();

  String getCurrency();

  Instant getOccurredAt();

  String getStatus();
}
