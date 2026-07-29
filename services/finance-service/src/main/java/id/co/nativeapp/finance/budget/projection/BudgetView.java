package id.co.nativeapp.finance.budget.projection;

import java.util.UUID;

/**
 * Read projection over a {@code budget} header row (Phase 5) — the detail read. Native-query
 * snake_case aliases map to these accessors; lives in {@code budget.projection} so the ArchUnit
 * layer rule keeps it to service + repository.
 */
public interface BudgetView {

  UUID getId();

  String getName();

  String getPeriod();

  String getCurrency();
}
