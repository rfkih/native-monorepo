package id.co.nativeapp.finance.tax.service;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.tax.domain.NetDirection;
import id.co.nativeapp.finance.tax.domain.TaxFiling;
import id.co.nativeapp.finance.tax.domain.TaxFilingNotFoundException;
import id.co.nativeapp.finance.tax.domain.TaxFilingStateException;
import id.co.nativeapp.finance.tax.domain.TaxFilingStatus;
import id.co.nativeapp.finance.tax.repository.TaxFilingRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for SETTLING a filed net-PAYABLE PPN return — recording
 * the payment of the net VAT to the tax authority (Phase 4, ADR 0017). It posts an ad-hoc balanced
 * entry {@code Dr VAT_PAYABLE (2300) / Cr CASH_CLEARING (1900)} for the net, routing the payment
 * through the same clearing account every other cash movement uses (so a later bank reconciliation
 * sweeps it — the cash cycle stays consistent), then transitions the filing FILED → SETTLED.
 *
 * <p><strong>Idempotency.</strong> Settlement is a one-shot FILED → SETTLED transition, guarded
 * up-front (a re-settle surfaces the {@link TaxFilingStateException} → 409, not a misleading
 * source_event_id conflict) and again in the domain mutator {@link TaxFiling#settle} as the
 * transactional backstop; the settlement entry's {@code source_event_id} (its own id, UNIQUE on
 * {@code journal_entry}) is the database backstop for a genuine race. Being a one-shot transition
 * (not a repeatable partial payment), it needs no {@code Idempotency-Key} — the same rationale as
 * bank reconciliation. A distinct proxy-invoked bean so the {@code @Transactional} + RLS aspect
 * engage.
 */
@Component
public class TaxSettlementWriter {

  private final TaxFilingRepository taxFilingRepository;
  private final RoleAccountResolver roleAccountResolver;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public TaxSettlementWriter(
      TaxFilingRepository taxFilingRepository,
      RoleAccountResolver roleAccountResolver,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.taxFilingRepository = Objects.requireNonNull(taxFilingRepository, "taxFilingRepository");
    this.roleAccountResolver = Objects.requireNonNull(roleAccountResolver, "roleAccountResolver");
    this.journalEntryRepository =
        Objects.requireNonNull(journalEntryRepository, "journalEntryRepository");
    this.journalLineRepository =
        Objects.requireNonNull(journalLineRepository, "journalLineRepository");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Settles the net-payable of a filed return: posts {@code Dr VAT_PAYABLE / Cr CASH_CLEARING} in
   * the CURRENT period (when the payment is made, mirroring an AP payment) and marks the filing
   * SETTLED.
   *
   * @return the filing id
   * @throws TaxFilingNotFoundException if the filing is not in the bound tenant (RLS-scoped)
   * @throws TaxFilingStateException if the return is already SETTLED, or is CREDITABLE / zero-net
   * @throws MismatchedPostingCurrencyException if the filing currency diverges from the current
   *     period's GL (→ 422)
   */
  @Transactional
  public UUID settle(UUID filingId) {
    Objects.requireNonNull(filingId, "filingId");
    TaxFiling filing =
        taxFilingRepository
            .findById(filingId)
            .orElseThrow(() -> new TaxFilingNotFoundException(filingId));

    // Guard up front (mirrors the bank-reconcile W1 fix): a re-settle / non-payable return must
    // surface the intended TaxFilingStateException (→ 409), not trip a source_event_id UNIQUE as a
    // misleading conflict, and must skip the doomed GL insert. The domain filing.settle(...) below
    // stays the transactional backstop.
    if (filing.getStatus() != TaxFilingStatus.FILED) {
      throw new TaxFilingStateException("tax filing is already settled: " + filingId);
    }
    if (filing.getNetDirection() != NetDirection.PAYABLE || filing.getNetMinor() <= 0L) {
      throw new TaxFilingStateException(
          "only a net-payable tax filing with a positive balance can be settled: " + filingId);
    }

    String companyId = TenantContext.require().companyId();
    Instant now = clock.instant();
    String period = LedgerPosting.periodOf(now);
    Money net = Money.ofMinor(filing.getNetMinor(), filing.getCurrency());
    requireConsistentGlCurrency(period, net);

    UUID entryId = UUID.randomUUID();
    JournalEntry entry = buildSettlementEntry(filing, period, now, entryId);
    persistEntry(entry, companyId);

    filing.settle(entryId, now);
    taxFilingRepository.save(filing);
    return filingId;
  }

  /**
   * Builds (but does not persist) the balanced ad-hoc settlement entry for {@code filing}: {@code Dr
   * VAT_PAYABLE / Cr CASH_CLEARING} for its net, posted into {@code period}. Public + pure (mocked
   * resolver, no DB) so {@code TaxFilingPostingTest} can assert the legs. {@code source_event_id =
   * entryId} (its own id, UNIQUE).
   */
  public JournalEntry buildSettlementEntry(
      TaxFiling filing, String period, Instant now, UUID entryId) {
    Currency currency = Currency.getInstance(filing.getCurrency());
    Money net = Money.ofMinor(filing.getNetMinor(), currency);
    String payableCode = requireMapped(AccountRole.VAT_PAYABLE, now);
    String clearingCode = requireMapped(AccountRole.CASH_CLEARING, now);
    List<JournalLine> lines =
        List.of(
            JournalLine.debit(entryId, 1, payableCode, net),
            JournalLine.credit(entryId, 2, clearingCode, net));
    return JournalEntry.balanced(
        entryId,
        period,
        now,
        "PPN VAT settlement (" + filing.getPeriod() + ")",
        currency.getCurrencyCode(),
        entryId,
        true,
        lines);
  }

  private String requireMapped(AccountRole role, Instant occurredAt) {
    String accountCode = roleAccountResolver.resolve(role, occurredAt);
    if (accountCode == null) {
      throw new IllegalStateException(
          "no role_account_map mapping for " + role + " at " + occurredAt);
    }
    return accountCode;
  }

  private void persistEntry(JournalEntry entry, String companyId) {
    entry.setCompanyId(companyId);
    journalEntryRepository.saveAndFlush(entry);
    for (var line : entry.getLines()) {
      line.setCompanyId(companyId);
      journalLineRepository.save(line);
    }
  }

  private void requireConsistentGlCurrency(String period, Money amount) {
    String incoming = amount.currency().getCurrencyCode();
    List<String> divergent =
        jdbcTemplate.query(
            "SELECT DISTINCT currency FROM journal_entry WHERE period = ? AND currency <> ?",
            (rs, rowNum) -> rs.getString(1).strip(),
            period,
            incoming);
    if (!divergent.isEmpty()) {
      throw new MismatchedPostingCurrencyException(period, divergent.getFirst(), incoming);
    }
  }
}
