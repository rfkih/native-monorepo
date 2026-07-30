package id.co.nativeapp.finance.tax.service;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.tax.domain.NoVatActivityException;
import id.co.nativeapp.finance.tax.domain.TaxFiling;
import id.co.nativeapp.finance.tax.repository.TaxFilingRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for FILING a period's PPN (VAT) return — the
 * money-critical novel piece of Phase 4 (ADR 0017; V31/V32). Like the Bank reconciliation writer
 * (and unlike AR/AP, which use the {@code posting_template} framework), filing posts an AD-HOC
 * balanced {@link JournalEntry} built directly here via {@link JournalLine#debit}/{@link
 * JournalLine#credit} — there is no {@code posting_template} or new {@code EventKind} for it,
 * because the net-VAT leg's <em>side</em> flips with the sign (a credit to VAT_PAYABLE when
 * payable, a debit to VAT_CREDIT_CARRYFORWARD when creditable).
 *
 * <p><strong>The netting entry (posted into the RETURN period, so it offsets that period's accruals
 * in the period trial balance):</strong>
 *
 * <ul>
 *   <li>{@code Dr VAT_OUTPUT (2200)} for the output VAT — clears the period's output-VAT liability.
 *   <li>{@code Cr VAT_INPUT (1300)} for the input VAT — clears the period's input-VAT asset.
 *   <li>net PAYABLE (output &gt; input): {@code Cr VAT_PAYABLE (2300)} for {@code output − input}.
 *   <li>net CREDITABLE (input &gt; output): {@code Dr VAT_CREDIT_CARRYFORWARD (1310)} for {@code
 *       input − output}.
 * </ul>
 *
 * A leg whose amount is zero is omitted; a zero net posts just the two clearing legs.
 *
 * <p><strong>Idempotency.</strong> A per-{@code (company, period, PPN)} advisory lock is taken at
 * the top, BEFORE the {@code findByPeriodAndTaxType} probe; an already-filed period returns its id
 * (a clean no-op, posting nothing). The netting entry's {@code source_event_id} is the filing id
 * (UNIQUE on {@code journal_entry}) — the database backstop behind the lock + the {@code
 * uq_tax_filing} UNIQUE(company, period, tax_type). A distinct proxy-invoked bean so the
 * {@code @Transactional} + RLS aspect engage (rule 5).
 */
@Component
public class TaxFilingWriter {

  private final VatReturnReader vatReturnReader;
  private final RoleAccountResolver roleAccountResolver;
  private final TaxFilingRepository taxFilingRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public TaxFilingWriter(
      VatReturnReader vatReturnReader,
      RoleAccountResolver roleAccountResolver,
      TaxFilingRepository taxFilingRepository,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.vatReturnReader = Objects.requireNonNull(vatReturnReader, "vatReturnReader");
    this.roleAccountResolver = Objects.requireNonNull(roleAccountResolver, "roleAccountResolver");
    this.taxFilingRepository = Objects.requireNonNull(taxFilingRepository, "taxFilingRepository");
    this.journalEntryRepository =
        Objects.requireNonNull(journalEntryRepository, "journalEntryRepository");
    this.journalLineRepository =
        Objects.requireNonNull(journalLineRepository, "journalLineRepository");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Files the PPN return for {@code (the bound company, period)}: computes the net from the GL,
   * posts the ad-hoc netting entry into the return period, and seals the {@code tax_filing} row.
   *
   * @return the filing id + whether it was freshly filed (an idempotent re-file returns the
   *     existing id with {@code created == false}, posting nothing)
   * @throws NoVatActivityException if the period has no VAT to file, or a net-void (negative)
   *     period
   * @throws MismatchedPostingCurrencyException if the period's currency diverges (→ 422)
   */
  @Transactional
  public FileReturnResult file(String period) {
    Objects.requireNonNull(period, "period");
    String companyId = TenantContext.require().companyId();

    // Serialize concurrent files of THIS (company, period, PPN), BEFORE the idempotency probe.
    taxFilingRepository.lockPeriod(companyId + ":" + period + ":" + TaxFiling.TAX_TYPE_PPN);

    Optional<TaxFiling> existing =
        taxFilingRepository.findByPeriodAndTaxType(period, TaxFiling.TAX_TYPE_PPN);
    if (existing.isPresent()) {
      // IDEMPOTENT no-op — the return is already filed; post nothing (created == false → 200).
      return new FileReturnResult(existing.get().getId(), false);
    }

    VatReturn vat = vatReturnReader.compute(period);
    if (vat.currency() == null) {
      throw new NoVatActivityException("period " + period + " has no VAT activity to file");
    }

    // Guard the single-base-currency invariant against the return period's GL (mirrors AR/AP). The
    // netting entry posts in vat.currency() — the period's own GL currency — so this is defence in
    // depth; a divergence would mean a broken invariant, not client input.
    requireConsistentGlCurrency(period, Money.ofMinor(0L, vat.currency()));

    Instant now = clock.instant();
    UUID filingId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    // The netting entry belongs to the RETURN period (so it offsets that period's 2200/1300 in the
    // period trial balance), occurred at `now`. source_event_id = the filing id (UNIQUE backstop).
    JournalEntry entry =
        buildFilingEntry(
            period,
            now,
            entryId,
            filingId,
            vat.currency(),
            vat.outputVatMinor(),
            vat.inputVatMinor());
    persistEntry(entry, companyId);

    TaxFiling filing =
        TaxFiling.file(
            filingId,
            period,
            vat.currency(),
            vat.outputVatMinor(),
            vat.inputVatMinor(),
            entryId,
            now);
    filing.setCompanyId(companyId);
    taxFilingRepository.save(filing);
    return new FileReturnResult(filingId, true);
  }

  /**
   * Builds (but does not persist) the balanced ad-hoc netting {@link JournalEntry} for filing the
   * PPN return of {@code period} with the given output/input VAT (minor units, {@code
   * currencyCode}). Public and pure (resolves account codes via {@link RoleAccountResolver}, no DB
   * writes) so {@code TaxFilingPostingTest} can assert the exact legs for every case with a mocked
   * resolver — mirroring how {@code ReconciliationWriter#buildEntry} is unit-tested.
   *
   * @param sourceEventId the entry's {@code source_event_id} (the filing id — UNIQUE idempotency
   *     key)
   * @throws NoVatActivityException if there is no VAT to file (both zero), or a net-void (negative)
   *     period — which this slice does not support (ADR 0017 deferral)
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public JournalEntry buildFilingEntry(
      String period,
      Instant now,
      UUID entryId,
      UUID sourceEventId,
      String currencyCode,
      long outputVatMinor,
      long inputVatMinor) {
    if (outputVatMinor < 0L || inputVatMinor < 0L) {
      throw new NoVatActivityException(
          "period "
              + period
              + " has negative VAT (voids exceeded issues); filing a net-void period is not"
              + " supported in this slice");
    }
    if (outputVatMinor == 0L && inputVatMinor == 0L) {
      throw new NoVatActivityException("period " + period + " has no VAT activity to file");
    }

    Currency currency = Currency.getInstance(currencyCode);
    long signedNet = Math.subtractExact(outputVatMinor, inputVatMinor);
    String outputCode = requireMapped(AccountRole.VAT_OUTPUT, now);
    String inputCode = requireMapped(AccountRole.VAT_INPUT, now);

    List<JournalLine> lines = new ArrayList<>(3);
    int lineNo = 1;
    if (outputVatMinor > 0L) {
      lines.add(
          JournalLine.debit(
              entryId, lineNo++, outputCode, Money.ofMinor(outputVatMinor, currency)));
    }
    if (inputVatMinor > 0L) {
      lines.add(
          JournalLine.credit(entryId, lineNo++, inputCode, Money.ofMinor(inputVatMinor, currency)));
    }
    if (signedNet > 0L) {
      String payableCode = requireMapped(AccountRole.VAT_PAYABLE, now);
      lines.add(
          JournalLine.credit(entryId, lineNo++, payableCode, Money.ofMinor(signedNet, currency)));
    } else if (signedNet < 0L) {
      String carryCode = requireMapped(AccountRole.VAT_CREDIT_CARRYFORWARD, now);
      lines.add(
          JournalLine.debit(entryId, lineNo++, carryCode, Money.ofMinor(-signedNet, currency)));
    }

    return JournalEntry.balanced(
        entryId,
        period,
        now,
        "PPN VAT return filed (" + period + ")",
        currency.getCurrencyCode(),
        sourceEventId,
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
    // saveAndFlush forces the journal_entry INSERT before the FK'd line INSERTs (same tx).
    journalEntryRepository.saveAndFlush(entry);
    for (var line : entry.getLines()) {
      line.setCompanyId(companyId);
      journalLineRepository.save(line);
    }
  }

  /**
   * Rejects a filing whose currency diverges from any journal entry already posted in the period
   * for this tenant (mirroring AR/AP's {@code requireConsistentGlCurrency}). Runs under RLS on the
   * {@code @Transactional} connection, so it only sees this tenant's GL.
   */
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
