package id.co.nativeapp.finance.pnl.service;

import id.co.nativeapp.finance.pnl.domain.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.domain.PnlFigures;
import id.co.nativeapp.finance.pnl.repository.ConsolidatedPnlRepository;
import id.co.nativeapp.finance.statements.service.IncomeStatementReader;
import id.co.nativeapp.money.Money;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the P&amp;L for the bound tenant — the query side of the P&amp;L (#21).
 *
 * <p>A {@code @Transactional(readOnly = true)} service bean so the proxy + auto-RLS aspect engage:
 * every read carries NO {@code WHERE company_id}; the result is constrained solely by the
 * auto-applied RLS policy (rule 5), so a caller only ever sees their own tenant's P&amp;L. The read
 * runs under whatever tenant the {@link id.co.nativeapp.tenant.TenantContext} scope is bound to
 * (set at the request edge by the gateway / dev filter).
 *
 * <p><strong>Two reads, one source of truth (ADR 0065).</strong> {@link #pnlForPeriod} — the
 * DASHBOARD P&amp;L — is DERIVED from the GL trial balance via the same computation that backs the
 * income statement ({@link IncomeStatementReader}), so the beranda "Laba bersih" and the Laba-Rugi
 * report are equal by construction. {@link #accumulatedPnlForPeriod} is the older {@code
 * consolidated_pnl} accumulator read — retained as the write-model's canonical single-currency view
 * (the exact view the write-path currency guards enforce against). The accumulator diverged from
 * the ledger because it was fed only by hand-picked POS writers and missed every other GL posting
 * (register-close cash variance, QRIS/platform fees, depreciation, AR/AP, service charge…); that is
 * precisely the divergence the GL-derived dashboard read fixes.
 */
@Service
public class PnlReader {

  private final IncomeStatementReader incomeStatementReader;
  private final ConsolidatedPnlRepository pnlRepository;

  public PnlReader(
      IncomeStatementReader incomeStatementReader, ConsolidatedPnlRepository pnlRepository) {
    this.incomeStatementReader = incomeStatementReader;
    this.pnlRepository = pnlRepository;
  }

  /**
   * The DASHBOARD consolidated P&amp;L (revenue, expense, net) for a period (bound tenant), or
   * empty when the period has NO GL entries at all. DERIVED from the GL trial balance (ADR 0065) so
   * it is identical to the income statement's totals — the dashboard can never disagree with the
   * Laba-Rugi report.
   *
   * <p>Behaviour vs. the old accumulator read: a period with GL entries that net to zero P&amp;L
   * (e.g. only balance-sheet postings) now returns a present, zero-legged {@link PnlFigures} (200
   * with zeros) rather than empty; only a period with no journal entries at all is empty. A
   * multi-currency period fails loud downstream as {@code GlMultiCurrencyException} (→ 422), not
   * the old read-time 500.
   *
   * @param period the accounting period {@code YYYY-MM}
   * @return the GL-derived P&amp;L figures, or empty when the period has no GL entries
   */
  @Transactional(readOnly = true)
  public Optional<PnlFigures> pnlForPeriod(String period) {
    return incomeStatementReader
        .read(period)
        .map(
            statement ->
                new PnlFigures(
                    statement.period(),
                    Money.ofMinor(statement.totalRevenueMinor(), statement.currency()),
                    Money.ofMinor(statement.totalExpenseMinor(), statement.currency()),
                    statement.usesIllustrativeRules()));
  }

  /**
   * The {@code consolidated_pnl} ACCUMULATOR row for a period (bound tenant), or empty if nothing
   * has been accumulated yet — the write-model's canonical single-currency read (distinct from the
   * GL-derived {@link #pnlForPeriod} the dashboard serves). It is NOT the dashboard figure; it
   * exists to verify the accumulator write path and mirrors the single-currency view the write-path
   * currency guards ({@code PnlReadModelWriter.requireConsistentCurrency}, {@code
   * LaborCostPostingWriter}) enforce.
   *
   * <p>The single-currency invariant is enforced at write time (a divergent-currency posting is
   * rejected before it can create a second-currency row), so the multi-currency branch here is a
   * defense-in-depth backstop the normal flow never reaches.
   *
   * @throws IllegalStateException if a period somehow holds more than one currency row
   */
  @Transactional(readOnly = true)
  public Optional<ConsolidatedPnl> accumulatedPnlForPeriod(String period) {
    List<ConsolidatedPnl> rows = pnlRepository.findByPeriod(period);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    if (rows.size() > 1) {
      throw new IllegalStateException(
          "Multiple currencies for period " + period + "; FX consolidation is not in scope");
    }
    return Optional.of(rows.getFirst());
  }
}
