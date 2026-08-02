package id.co.nativeapp.finance.labor.service;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.projection.JournalLineReversalView;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.labor.domain.LiabilityState;
import id.co.nativeapp.finance.labor.domain.NegativeLiabilityBucketException;
import id.co.nativeapp.finance.labor.domain.PayrollLiabilityBucketEmptyException;
import id.co.nativeapp.finance.labor.domain.PayrollLiabilityNotSettleableException;
import id.co.nativeapp.finance.labor.domain.PayrollRunLedger;
import id.co.nativeapp.finance.labor.domain.PayrollRunLedgerNotFoundException;
import id.co.nativeapp.finance.labor.domain.PayrollSettlement;
import id.co.nativeapp.finance.labor.domain.PayrollSettlementAlreadySettledException;
import id.co.nativeapp.finance.labor.domain.PayrollSettlementIdempotencyKeyConflictException;
import id.co.nativeapp.finance.labor.domain.SettlementKind;
import id.co.nativeapp.finance.labor.repository.PayrollRunLedgerRepository;
import id.co.nativeapp.finance.labor.repository.PayrollSettlementRepository;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for SETTLING one payroll-liability bucket of a POSTED,
 * non-superseded run (ADR 0032, Track P phase P5) — the payroll counterpart of {@code
 * id.co.nativeapp.finance.tax.service.TaxSettlementWriter} and {@code
 * id.co.nativeapp.finance.assets.service.AssetDisposalWriter}, combining both precedents: it posts
 * an ad-hoc {@code Dr <bucket role account> / Cr CASH_CLEARING (1900)} entry (TaxSettlementWriter's
 * shape) guarded by a REQUIRED, per-attempt {@code Idempotency-Key} with same-key replay and
 * path/body-authoritative conflict detection (AssetDisposalWriter's idiom — settlement, unlike a
 * filing status flip, is a genuinely repeatable client action).
 *
 * <p><strong>The settled amount is NEVER client-supplied.</strong> It is read back from the run's
 * OWN liability {@code journal_entry} — the same entry {@code PayrollLiabilityWriter} built when
 * the run posted — by resolving the bucket's {@link AccountRole} to the account code that role
 * mapped to AT THE ENTRY'S {@code occurred_at} (mirroring the READ design documented on {@link
 * id.co.nativeapp.finance.labor.projection.PayrollLiabilityRunView}) and summing that account
 * code's lines' {@code credit_minor - debit_minor} (positive = a credit-side bucket, negative = the
 * December Art-17 refund's debit-side bucket).
 *
 * <p><strong>A negative bucket is rejected (422, v1 residual, ADR 0032 §P5).</strong> A negative
 * payable is in substance a receivable from the tax office, netted against the next remittance —
 * not a standalone payment; see {@link NegativeLiabilityBucketException}.
 */
@Component
public class PayrollSettlementWriter {

  private final PayrollRunLedgerRepository runLedgerRepository;
  private final PayrollSettlementRepository settlementRepository;
  private final RoleAccountResolver roleAccountResolver;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public PayrollSettlementWriter(
      PayrollRunLedgerRepository runLedgerRepository,
      PayrollSettlementRepository settlementRepository,
      RoleAccountResolver roleAccountResolver,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.runLedgerRepository = Objects.requireNonNull(runLedgerRepository, "runLedgerRepository");
    this.settlementRepository =
        Objects.requireNonNull(settlementRepository, "settlementRepository");
    this.roleAccountResolver = Objects.requireNonNull(roleAccountResolver, "roleAccountResolver");
    this.journalEntryRepository =
        Objects.requireNonNull(journalEntryRepository, "journalEntryRepository");
    this.journalLineRepository =
        Objects.requireNonNull(journalLineRepository, "journalLineRepository");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Settles one liability bucket of {@code runLedgerId}: posts {@code Dr <bucket role account> / Cr
   * CASH_CLEARING} in the CURRENT period (mirrors {@code TaxSettlementWriter} — the payment is made
   * now, regardless of the liability's original period) and records the one-shot {@link
   * PayrollSettlement} row.
   *
   * @throws PayrollSettlementIdempotencyKeyConflictException if the key already settled a DIFFERENT
   *     {@code (run, kind)} — 409
   * @throws PayrollRunLedgerNotFoundException if the run is not in the bound tenant — 404
   * @throws PayrollSettlementAlreadySettledException if this {@code (run, kind)} is already settled
   *     under a different key — 409
   * @throws PayrollLiabilityNotSettleableException if the run's liability is not POSTED (never
   *     recognised, or SUPERSEDED) — 409
   * @throws PayrollLiabilityBucketEmptyException if the run never recognised this bucket — 409
   * @throws NegativeLiabilityBucketException if the bucket's recognised amount is negative — 422
   * @throws MismatchedPostingCurrencyException if the settlement currency diverges from the current
   *     period's GL — 422
   */
  @Transactional
  public PayrollSettlementResult settle(
      UUID runLedgerId, SettlementKind kind, String idempotencyKey) {
    Objects.requireNonNull(runLedgerId, "runLedgerId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");

    // 1) Replay-by-key probe FIRST (AssetDisposalWriter idiom): a genuine retry never re-reads run
    //    state — the original attempt already validated everything.
    Optional<PayrollSettlement> byKey = settlementRepository.findByIdempotencyKey(idempotencyKey);
    if (byKey.isPresent()) {
      PayrollSettlement existing = byKey.get();
      if (existing.getPayrollRunLedgerId().equals(runLedgerId) && existing.getKind() == kind) {
        return new PayrollSettlementResult(existing.getId(), false);
      }
      throw new PayrollSettlementIdempotencyKeyConflictException(
          runLedgerId, kind.name(), existing.getPayrollRunLedgerId(), existing.getKind().name());
    }

    PayrollRunLedger runRow =
        runLedgerRepository
            .findById(runLedgerId)
            .orElseThrow(() -> new PayrollRunLedgerNotFoundException(runLedgerId));

    // 2) A genuine second attempt (different key) against an already-settled bucket → 409.
    if (settlementRepository.findByPayrollRunLedgerIdAndKind(runLedgerId, kind).isPresent()) {
      throw new PayrollSettlementAlreadySettledException(runLedgerId, kind.name());
    }

    // 3) The liability dimension must be POSTED (not never-recognised, not SUPERSEDED).
    if (runRow.getLiabilityState() != LiabilityState.POSTED) {
      String reason =
          runRow.getLiabilityState() == LiabilityState.SUPERSEDED
              ? "the run's liability was superseded by a later run — settling a superseded run is"
                  + " forbidden"
              : "no payroll liability has been recognised for this run yet";
      throw new PayrollLiabilityNotSettleableException(runLedgerId, reason);
    }

    // 4) Read the bucket's amount back from the run's OWN liability entry — never client-supplied.
    JournalEntry liabilityEntry =
        journalEntryRepository
            .findById(runRow.getLiabilityEntryId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "payroll run ledger "
                            + runLedgerId
                            + " is liability_state=POSTED but its liability_entry_id "
                            + runRow.getLiabilityEntryId()
                            + " does not exist"));
    Instant liabilityOccurredAt = liabilityEntry.getOccurredAt();
    String bucketAccountCode = resolveOrSuspense(kind.liabilityRole(), liabilityOccurredAt);
    List<JournalLineReversalView> liabilityLines =
        journalLineRepository.findLinesByEntryId(runRow.getLiabilityEntryId());
    long signedAmountMinor =
        liabilityLines.stream()
            .filter(l -> l.getAccountCode().equals(bucketAccountCode))
            .mapToLong(l -> l.getCreditMinor() - l.getDebitMinor())
            .sum();

    if (signedAmountMinor == 0L) {
      throw new PayrollLiabilityBucketEmptyException(runLedgerId, kind.name());
    }
    if (signedAmountMinor < 0L) {
      throw new NegativeLiabilityBucketException(kind.name());
    }

    Money amount = Money.ofMinor(signedAmountMinor, runRow.currencyCode());

    String companyId = TenantContext.require().companyId();
    Instant now = clock.instant();
    String period = LedgerPosting.periodOf(now);
    requireConsistentGlCurrency(period, amount);

    UUID entryId = UUID.randomUUID();
    JournalEntry entry = buildSettlementEntry(kind, amount, period, now, entryId);
    persistEntry(entry, companyId);

    PayrollSettlement settlement =
        new PayrollSettlement(runLedgerId, kind, amount, entryId, now, idempotencyKey);
    settlement.setCompanyId(companyId);
    settlementRepository.save(settlement);

    return new PayrollSettlementResult(settlement.getId(), true);
  }

  /**
   * Builds (but does not persist) the balanced settlement entry: {@code Dr <bucket role account> /
   * Cr CASH_CLEARING} for {@code amount}. Public + pure (mocked resolver, no DB) so a unit test can
   * assert the exact legs, mirroring {@code TaxSettlementWriter#buildSettlementEntry}. {@code
   * source_event_id = entryId} (its own id, UNIQUE).
   */
  public JournalEntry buildSettlementEntry(
      SettlementKind kind, Money amount, String period, Instant now, UUID entryId) {
    String bucketCode = requireMapped(kind.liabilityRole(), now);
    String clearingCode = requireMapped(AccountRole.CASH_CLEARING, now);
    List<JournalLine> lines =
        List.of(
            JournalLine.debit(entryId, 1, bucketCode, amount),
            JournalLine.credit(entryId, 2, clearingCode, amount));
    return JournalEntry.balanced(
        entryId,
        period,
        now,
        "Payroll settlement — " + kind.name(),
        amount.currency().getCurrencyCode(),
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

  /**
   * Resolves a role to an account code exactly as {@code PayrollLiabilityWriter} did when it BUILT
   * the liability entry — falling back to {@link RoleAccountResolver#SUSPENSE_ACCOUNT_CODE} if
   * unmapped, so the historical line lookup here finds the SAME account code the entry actually
   * used, even if it was posted to suspense at the time.
   */
  private String resolveOrSuspense(AccountRole role, Instant occurredAt) {
    String accountCode = roleAccountResolver.resolve(role, occurredAt);
    return accountCode == null ? RoleAccountResolver.SUSPENSE_ACCOUNT_CODE : accountCode;
  }

  private void persistEntry(JournalEntry entry, String companyId) {
    entry.setCompanyId(companyId);
    journalEntryRepository.saveAndFlush(entry);
    for (JournalLine line : entry.getLines()) {
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
