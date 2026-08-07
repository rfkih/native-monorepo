package id.co.nativeapp.finance.bank.service;

import id.co.nativeapp.finance.bank.domain.BankStatementLine;
import id.co.nativeapp.finance.bank.domain.ReconciliationCategory;
import id.co.nativeapp.finance.bank.domain.ReconciliationStateException;
import id.co.nativeapp.finance.bank.domain.StatementLineNotFoundException;
import id.co.nativeapp.finance.bank.domain.StatementLineStatus;
import id.co.nativeapp.finance.bank.repository.BankStatementLineRepository;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for reconciling one {@link BankStatementLine} — the
 * money-critical novel piece of Phase 3 bank reconciliation (ADR 0016; V29/V30). Unlike AR/AP's
 * {@code JournalPostingService.buildEntryFromBreakdown} (a {@code posting_template}-driven
 * builder), a reconciliation posts an AD-HOC balanced 2-line {@link JournalEntry} built directly
 * here with {@link JournalLine#debit}/{@link JournalLine#credit} — there is no new {@code
 * EventKind} or {@code posting_template} for this (V30's SME note).
 *
 * <p><strong>The two legs.</strong> {@code BANK} (→ 1000, the single control account every bank
 * account shares) always takes one leg; the contra takes the other, resolved from the {@link
 * ReconciliationCategory}: {@code CLEARING} → {@code CASH_CLEARING} (1900, the sweep from
 * cash-in-transit), {@code INTEREST} → {@code INTEREST_INCOME} (4100, deposit only), {@code
 * BANK_FEE} → {@code BANK_CHARGES} (5400, withdrawal only), {@code QRIS_CLEARING} → {@code
 * QRIS_CLEARING} (1901, deposit only). A deposit (positive {@code amount_minor}) debits BANK /
 * credits the contra; a withdrawal (negative) debits the contra / credits BANK.
 *
 * <p><strong>The optional QRIS MDR fee leg (ADR 0045).</strong> {@code QRIS_CLEARING} alone may
 * carry an acquirer merchant-discount-rate fee ({@code feeMinor}, read off the settlement report by
 * the operator): {@code Dr BANK (net) + Dr QRIS_FEE_EXPENSE (fee, 5720) / Cr QRIS_CLEARING (gross =
 * net + fee)}. A zero/omitted fee posts the ordinary 2-leg transfer — a zero-amount journal line is
 * never written (the {@code PlatformSettlementWriter} precedent, V44/V45). A non-null fee on any
 * other category, or a negative fee, is rejected ({@link IllegalArgumentException} → 400).
 *
 * <p><strong>No double-reconcile.</strong> The transition guard lives in the domain mutator ({@link
 * BankStatementLine#reconcile}), invoked as the LAST step of this method — if the line is no longer
 * UNRECONCILED (e.g. a concurrent reconcile already ran and this transaction's read was stale), it
 * throws {@link ReconciliationStateException} and the whole {@code @Transactional} method —
 * including the journal entry already {@code saveAndFlush}'d above it — rolls back atomically, so
 * no stray GL entry survives a rejected reconcile. {@code source_event_id = lineId} is additionally
 * {@code UNIQUE} on {@code journal_entry} (V13): a genuinely concurrent race (two transactions both
 * reading UNRECONCILED before either commits) is caught by that constraint as a database backstop
 * behind this status guard — the loser's insert fails and its transaction rolls back, so money is
 * never posted twice for the same line.
 */
@Component
public class ReconciliationWriter {

  private final BankStatementLineRepository statementLineRepository;
  private final RoleAccountResolver roleAccountResolver;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public ReconciliationWriter(
      BankStatementLineRepository statementLineRepository,
      RoleAccountResolver roleAccountResolver,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.statementLineRepository =
        Objects.requireNonNull(statementLineRepository, "statementLineRepository");
    this.roleAccountResolver = Objects.requireNonNull(roleAccountResolver, "roleAccountResolver");
    this.journalEntryRepository =
        Objects.requireNonNull(journalEntryRepository, "journalEntryRepository");
    this.journalLineRepository =
        Objects.requireNonNull(journalLineRepository, "journalLineRepository");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Reconciles {@code lineId} against {@code category} with no fee leg — the pre-ADR-0045 overload,
   * kept for callers that never touch QRIS. Delegates to {@link #reconcile(UUID,
   * ReconciliationCategory, Long)} with a {@code null} fee.
   *
   * @return {@code lineId} (the caller re-reads the fresh line detail)
   * @throws StatementLineNotFoundException if the line is not in the bound tenant (RLS-scoped)
   * @throws ReconciliationStateException if the line is not UNRECONCILED (no double-reconcile)
   * @throws IllegalArgumentException if {@code category} does not match the line's direction
   * @throws MismatchedPostingCurrencyException if the line's currency diverges from a currency
   *     already posted in the period for this tenant
   */
  @Transactional
  public UUID reconcile(UUID lineId, ReconciliationCategory category) {
    return reconcile(lineId, category, null);
  }

  /**
   * Reconciles {@code lineId} against {@code category}: validates the category is a legal pairing
   * for the line's direction, resolves the BANK + contra (+ optional QRIS fee, ADR 0045) account
   * codes, posts the balanced transfer entry, and links it back to the line.
   *
   * @param feeMinor the optional QRIS MDR fee in minor units ({@code null} or {@code 0} = no fee);
   *     valid ONLY when {@code category} is {@link ReconciliationCategory#QRIS_CLEARING}
   * @return {@code lineId} (the caller re-reads the fresh line detail)
   * @throws StatementLineNotFoundException if the line is not in the bound tenant (RLS-scoped)
   * @throws ReconciliationStateException if the line is not UNRECONCILED (no double-reconcile)
   * @throws IllegalArgumentException if {@code category} does not match the line's direction, if
   *     {@code feeMinor} is non-null for a category other than {@code QRIS_CLEARING}, or if {@code
   *     feeMinor} is negative
   * @throws MismatchedPostingCurrencyException if the line's currency diverges from a currency
   *     already posted in the period for this tenant
   */
  @Transactional
  public UUID reconcile(UUID lineId, ReconciliationCategory category, Long feeMinor) {
    Objects.requireNonNull(category, "category");
    BankStatementLine line =
        statementLineRepository
            .findById(lineId)
            .orElseThrow(() -> new StatementLineNotFoundException(lineId));
    // Guard up front (code-review W1): a re-reconcile must surface the intended
    // ReconciliationStateException (→ 409 "already reconciled"), not trip the source_event_id
    // UNIQUE
    // as a misleading "concurrent conflict, please retry", and must skip the doomed GL insert. The
    // domain line.reconcile(...) below stays the transactional backstop for a concurrent race.
    if (line.getStatus() != StatementLineStatus.UNRECONCILED) {
      throw new ReconciliationStateException("statement line is already reconciled: " + lineId);
    }

    Instant now = clock.instant();
    String period = LedgerPosting.periodOf(now);
    Money amount = Money.ofMinor(Math.abs(line.getAmountMinor()), line.getCurrency());
    requireConsistentGlCurrency(period, amount);

    UUID entryId = UUID.randomUUID();
    JournalEntry entry = buildEntry(line, category, now, entryId, feeMinor);

    String companyId = TenantContext.require().companyId();
    persistEntry(entry, companyId);

    line.reconcile(category, entry.getId());
    statementLineRepository.save(line);
    return lineId;
  }

  /**
   * Builds (but does not persist) the balanced ad-hoc {@link JournalEntry} for reconciling {@code
   * line} against {@code category} at {@code now} with no fee leg, keyed on {@code entryId} — the
   * pre-ADR-0045 overload, kept for callers/tests that never touch QRIS. Delegates to {@link
   * #buildEntry(BankStatementLine, ReconciliationCategory, Instant, UUID, Long)} with a {@code
   * null} fee.
   *
   * @throws IllegalArgumentException if {@code category} is not a legal pairing for the line's
   *     direction (deposit vs withdrawal)
   */
  public JournalEntry buildEntry(
      BankStatementLine line, ReconciliationCategory category, Instant now, UUID entryId) {
    return buildEntry(line, category, now, entryId, null);
  }

  /**
   * Builds (but does not persist) the balanced ad-hoc {@link JournalEntry} for reconciling {@code
   * line} against {@code category} at {@code now}, keyed on {@code entryId}. Side-effect free
   * besides resolving account codes via {@link RoleAccountResolver} — public and pure so {@code
   * ReconcilePostingTest} can assert the exact debit/credit legs for every category/direction
   * pairing with a mocked resolver and no database, mirroring how {@code
   * JournalPostingService#buildEntryFromBreakdown} is unit-tested.
   *
   * <p>A deposit (positive {@code amount_minor}) debits {@code BANK} / credits the contra; a
   * withdrawal (negative) debits the contra / credits {@code BANK}. When {@code feeMinor} resolves
   * to a strictly positive amount (only legal for {@link ReconciliationCategory#QRIS_CLEARING}, ADR
   * 0045), a third leg is added: {@code Dr BANK (net) + Dr QRIS_FEE_EXPENSE (fee) / Cr
   * QRIS_CLEARING (gross = net + fee)} — mirroring {@code PlatformSettlementWriter}'s "zero-amount
   * legs are omitted" rule, so a zero/null fee posts the ordinary 2-leg transfer.
   *
   * @param feeMinor the optional QRIS MDR fee in minor units ({@code null} or {@code 0} = no fee)
   * @throws IllegalArgumentException if {@code category} is not a legal pairing for the line's
   *     direction (deposit vs withdrawal), if {@code feeMinor} is non-null for a category other
   *     than {@code QRIS_CLEARING}, or if {@code feeMinor} is negative
   */
  public JournalEntry buildEntry(
      BankStatementLine line,
      ReconciliationCategory category,
      Instant now,
      UUID entryId,
      Long feeMinor) {
    long amountMinor = line.getAmountMinor();
    // Guard the abs overflow (code-review S1): Math.abs(Long.MIN_VALUE) stays negative and would
    // otherwise reach JournalLine as a non-positive amount (a 500). Reject as bad input (→ 400).
    if (amountMinor == Long.MIN_VALUE) {
      throw new IllegalArgumentException("statement line amount is out of range");
    }
    boolean isDeposit = amountMinor > 0;
    validateCategoryForDirection(category, isDeposit);
    long fee = validateFee(category, feeMinor);

    Money amount = Money.ofMinor(Math.abs(amountMinor), line.getCurrency());
    String bankCode = requireMapped(AccountRole.BANK, now);
    String contraCode = requireMapped(contraRole(category), now);

    List<JournalLine> lines = new ArrayList<>(3);
    if (fee > 0) {
      // Only reachable for QRIS_CLEARING (validateFee) — deposit-only (validateCategoryForDirection
      // already rejected a withdrawal above), so `amount` here is the net QRIS settlement deposit.
      Money feeAmount = Money.ofMinor(fee, line.getCurrency());
      String feeCode = requireMapped(AccountRole.QRIS_FEE_EXPENSE, now);
      Money gross = amount.plus(feeAmount);
      lines.add(JournalLine.debit(entryId, 1, bankCode, amount));
      lines.add(JournalLine.debit(entryId, 2, feeCode, feeAmount));
      lines.add(JournalLine.credit(entryId, 3, contraCode, gross));
    } else if (isDeposit) {
      lines.add(JournalLine.debit(entryId, 1, bankCode, amount));
      lines.add(JournalLine.credit(entryId, 2, contraCode, amount));
    } else {
      lines.add(JournalLine.debit(entryId, 1, contraCode, amount));
      lines.add(JournalLine.credit(entryId, 2, bankCode, amount));
    }

    String period = LedgerPosting.periodOf(now);
    return JournalEntry.balanced(
        entryId,
        period,
        now,
        "Bank reconciliation (" + category + ")",
        amount.currency().getCurrencyCode(),
        line.getId(),
        true,
        lines);
  }

  /**
   * Validates {@code category} against the line's direction: {@code CLEARING} is valid both ways;
   * {@code INTEREST} and {@code QRIS_CLEARING} only for a deposit; {@code BANK_FEE} only for a
   * withdrawal.
   */
  private static void validateCategoryForDirection(
      ReconciliationCategory category, boolean isDeposit) {
    switch (category) {
      case CLEARING -> {
        // valid for both a deposit and a withdrawal
      }
      case INTEREST -> {
        if (!isDeposit) {
          throw new IllegalArgumentException(
              "INTEREST can only reconcile a deposit (a positive statement line amount)");
        }
      }
      case QRIS_CLEARING -> {
        if (!isDeposit) {
          throw new IllegalArgumentException(
              "QRIS_CLEARING can only reconcile a deposit (a positive statement line amount)");
        }
      }
      // BANK_FEE — the only remaining ReconciliationCategory value.
      default -> {
        if (isDeposit) {
          throw new IllegalArgumentException(
              "BANK_FEE can only reconcile a withdrawal (a negative statement line amount)");
        }
      }
    }
  }

  /**
   * Validates the optional QRIS MDR fee leg (ADR 0045) and normalizes it to minor units: only
   * {@link ReconciliationCategory#QRIS_CLEARING} may carry a POSITIVE fee, and a supplied fee must
   * not be negative. An explicit {@code 0} means the same as {@code null} — "no fee" — on EVERY
   * category (code review W2: a client that always sends the field must not be rejected for writing
   * the semantically-identical zero).
   *
   * @return {@code 0} when {@code feeMinor} is {@code null} or {@code 0}, else {@code feeMinor}
   * @throws IllegalArgumentException if {@code feeMinor} is negative, or is positive for a category
   *     other than {@code QRIS_CLEARING}
   */
  private static long validateFee(ReconciliationCategory category, Long feeMinor) {
    if (feeMinor == null) {
      return 0L;
    }
    if (feeMinor < 0L) {
      throw new IllegalArgumentException("feeMinor must not be negative: " + feeMinor);
    }
    if (feeMinor == 0L) {
      return 0L;
    }
    if (category != ReconciliationCategory.QRIS_CLEARING) {
      throw new IllegalArgumentException(
          "feeMinor is only valid for QRIS_CLEARING (category=" + category + ")");
    }
    return feeMinor;
  }

  private static AccountRole contraRole(ReconciliationCategory category) {
    return switch (category) {
      case CLEARING -> AccountRole.CASH_CLEARING;
      case INTEREST -> AccountRole.INTEREST_INCOME;
      case BANK_FEE -> AccountRole.BANK_CHARGES;
      case QRIS_CLEARING -> AccountRole.QRIS_CLEARING;
    };
  }

  /**
   * Resolves {@code role} via {@link RoleAccountResolver}, failing loud (rather than posting to an
   * undefined account) if V30's seed is somehow missing at {@code occurredAt} — this should never
   * happen in a correctly migrated database (BANK/INTEREST_INCOME/BANK_CHARGES are seeded effective
   * from 2000-01-01, and CASH_CLEARING since V13), so an unmapped role here is an internal
   * data-integrity fault, not a client-fixable condition.
   */
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
    for (var journalLine : entry.getLines()) {
      journalLine.setCompanyId(companyId);
      journalLineRepository.save(journalLine);
    }
  }

  /**
   * Rejects a reconciliation posting whose currency diverges from any journal entry already posted
   * in the period for this tenant (mirroring AR/AP's {@code requireConsistentGlCurrency} verbatim).
   * Runs under RLS on the {@code @Transactional} connection, so it sees only this tenant's GL.
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
