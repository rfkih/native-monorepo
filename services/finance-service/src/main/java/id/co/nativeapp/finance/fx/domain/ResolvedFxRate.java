package id.co.nativeapp.finance.fx.domain;

import id.co.nativeapp.money.FxRate;
import java.time.LocalDate;

/**
 * A resolved {@link FxRate} paired with the {@code effective_from} date of the row it came from.
 *
 * <p>The {@link FxRate} value type (in {@code libs/money}) is deliberately a PURE rate value — it
 * carries no effective-dating, so it stays reusable by P3d's cross-currency consolidation and holds
 * no persistence concerns. But the presentation response must surface {@code effectiveFrom} so a
 * reader can reproduce the figure exactly (the auditable {@code appliedRates[]}). This thin
 * finance-local pairing carries that one extra field out of the provider without polluting the
 * shared value type.
 *
 * @param rate the resolved rate value
 * @param effectiveFrom the {@code effective_from} of the resolved {@code fx_rate} row
 */
public record ResolvedFxRate(FxRate rate, LocalDate effectiveFrom) {}
