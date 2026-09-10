package id.co.nativeapp.finance.platform.service;

import id.co.nativeapp.finance.bank.domain.ReconciliationCategory;
import id.co.nativeapp.finance.bank.dto.StatementLineResponse;
import id.co.nativeapp.finance.bank.projection.BankAccountView;
import id.co.nativeapp.finance.bank.repository.BankAccountRepository;
import id.co.nativeapp.finance.bank.service.ReconciliationWriter;
import id.co.nativeapp.finance.bank.service.StatementLineInput;
import id.co.nativeapp.finance.bank.service.StatementLineWriter;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.service.GeneralLedgerWriter;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.platform.domain.PlatformNetExceedsGrossException;
import id.co.nativeapp.finance.platform.domain.PlatformOverSettlementException;
import id.co.nativeapp.finance.platform.domain.PlatformSettlement;
import id.co.nativeapp.finance.platform.domain.PlatformSettlementAlreadyVoidedException;
import id.co.nativeapp.finance.platform.domain.PlatformSettlementIdempotencyKeyConflictException;
import id.co.nativeapp.finance.platform.domain.PlatformSettlementNotFoundException;
import id.co.nativeapp.finance.platform.domain.SettlementAllocation;
import id.co.nativeapp.finance.platform.domain.SettlementAllocation.AllocatedLine;
import id.co.nativeapp.finance.platform.domain.SettlementAllocation.SourceLine;
import id.co.nativeapp.finance.platform.domain.SettlementSourceKind;
import id.co.nativeapp.finance.platform.dto.OverdueSourceResponse;
import id.co.nativeapp.finance.platform.dto.PayoutSourceResponse;
import id.co.nativeapp.finance.platform.dto.PlatformOutstandingResponse;
import id.co.nativeapp.finance.platform.dto.PlatformSettlementResponse;
import id.co.nativeapp.finance.platform.dto.PlatformSettlementResult;
import id.co.nativeapp.finance.platform.dto.PlatformSettlementSummaryResponse;
import id.co.nativeapp.finance.platform.projection.PlatformSettlementSummaryView;
import id.co.nativeapp.finance.platform.repository.PlatformSettlementRepository;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for recording one PLATFORM PAYOUT (ADR 0036 Phase C) —
 * the PayrollSettlementWriter idiom applied to the per-channel platform receivable: a REQUIRED
 * {@code Idempotency-Key} with replay-by-key-first and payload-verification 409, an advisory lock
 * serializing concurrent settlements of the same channel, a GUARDED single-statement decrement of
 * the {@code platform_receivable} accumulator (over-settlement loses atomically), and a pure {@link
 * #buildSettlementEntry} so a unit test can assert the exact legs:
 *
 * <pre>Dr CASH_CLEARING (net) + Dr PLATFORM_FEE_EXPENSE (fee = gross − net) / Cr
 * PLATFORM_RECEIVABLE (gross)</pre>
 *
 * <p>The payout's bank-statement line then reconciles through the existing ADR-0016 CLEARING sweep
 * — bank reconciliation stays the single Dr-BANK writer. Zero-amount legs are omitted (a fee-free
 * payout posts 2 lines; a net-zero payout — the whole gross eaten by fees — posts fee + receivable
 * only). v1 books the WHOLE fee to expense (commission PPN split deferred; ILLUSTRATIVE, SME-gated
 * — ADR 0036).
 */
@Component
public class PlatformSettlementWriter {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(PlatformSettlementWriter.class);

  /**
   * The fleet business date (Asia/Jakarta — the same zone {@code findSummary} shifts {@code
   * settled_at} into). Used for the statement line a payout deposits, so its date matches the
   * date the bank will show.
   */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Jakarta");

  private final PlatformSettlementRepository settlementRepository;
  private final PlatformReceivableWriter platformReceivable;
  private final BankAccountRepository bankAccountRepository;
  private final StatementLineWriter statementLineWriter;
  private final ReconciliationWriter reconciliationWriter;
  private final GeneralLedgerWriter generalLedgerWriter;
  private final RoleAccountResolver roleAccountResolver;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public PlatformSettlementWriter(
      PlatformSettlementRepository settlementRepository,
      PlatformReceivableWriter platformReceivable,
      BankAccountRepository bankAccountRepository,
      StatementLineWriter statementLineWriter,
      ReconciliationWriter reconciliationWriter,
      GeneralLedgerWriter generalLedgerWriter,
      RoleAccountResolver roleAccountResolver,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.settlementRepository = settlementRepository;
    this.platformReceivable = platformReceivable;
    this.bankAccountRepository = bankAccountRepository;
    this.statementLineWriter = statementLineWriter;
    this.reconciliationWriter = reconciliationWriter;
    this.generalLedgerWriter = generalLedgerWriter;
    this.roleAccountResolver = roleAccountResolver;
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  /**
   * Records a single-source payout — the ADR 0036 shape, kept for the existing API. Delegates to
   * {@link #settleSources} so there is exactly ONE money path: two paths would be free to drift,
   * and a drift between them would be a silently mis-posted payout.
   *
   * @throws PlatformSettlementIdempotencyKeyConflictException replayed key, different payload (409)
   * @throws PlatformNetExceedsGrossException net &gt; gross — negative commission (422)
   * @throws PlatformOverSettlementException the channel's outstanding does not cover gross (422)
   */
  @Transactional
  public PlatformSettlementResult settle(
      String channelCode, long grossMinor, long netMinor, String currency, String idempotencyKey) {
    Objects.requireNonNull(channelCode, "channelCode");
    String normalized = channelCode.strip().toUpperCase(java.util.Locale.ROOT);
    return settleSources(
        normalized,
        List.of(new SourceLine(SettlementSourceKind.MARKETPLACE, normalized, grossMinor)),
        netMinor,
        currency,
        idempotencyKey);
  }

  /**
   * Records ONE payout that cleared one or more sources (ADR 0076 phase 2): a merchant on Shopee's
   * QRIS receives a single transfer covering both its ShopeeFood orders and its counter QRIS, and
   * enters the net once — the figure on the bank statement.
   *
   * <p>Each line's share of {@code Σgross − net} books to its OWN fee account, so a marketplace
   * commission and an MDR stay distinguishable. The clearing debit stays CASH_CLEARING: bank
   * reconciliation remains the only Dr-BANK writer (ADR 0016), so a payout moves money to clearing
   * and the statement line sweeps it to the bank.
   *
   * @throws PlatformSettlementIdempotencyKeyConflictException replayed key, different payload (409)
   * @throws PlatformNetExceedsGrossException net &gt; total gross — negative commission (422)
   * @throws PlatformOverSettlementException a source's outstanding does not cover its gross (422)
   */
  @Transactional
  public PlatformSettlementResult settleSources(
      String sourceCode,
      List<SourceLine> requestedLines,
      long netMinor,
      String currency,
      String idempotencyKey) {
    Objects.requireNonNull(sourceCode, "sourceCode");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    if (requestedLines == null || requestedLines.isEmpty()) {
      throw new IllegalArgumentException("a payout must clear at least one source");
    }
    String payer = sourceCode.strip().toUpperCase(java.util.Locale.ROOT);

    // Normalized ORDER, so the same set of sources always builds the same journal and compares
    // equal on replay; normalized CASE, because restaurant stores channel codes uppercase and a
    // lowercase request would otherwise miss its accumulator row and masquerade as an
    // over-settlement (ADR 0036 review S1).
    List<SourceLine> lines =
        SettlementAllocation.normalize(
            requestedLines.stream()
                .map(
                    l ->
                        new SourceLine(
                            l.kind(),
                            l.channelCode().strip().toUpperCase(java.util.Locale.ROOT),
                            l.grossMinor()))
                .toList());
    if (lines.stream().map(l -> l.kind() + "|" + l.channelCode()).distinct().count()
        != lines.size()) {
      throw new IllegalArgumentException("a payout may clear each source at most once");
    }
    long grossMinor = 0L;
    for (SourceLine line : lines) {
      grossMinor = Math.addExact(grossMinor, line.grossMinor());
    }

    // 1) Replay-by-key probe FIRST (PayrollSettlementWriter idiom): a genuine retry never re-runs
    //    the decrements — the original attempt already moved the money.
    Optional<PlatformSettlement> byKey = settlementRepository.findByIdempotencyKey(idempotencyKey);
    if (byKey.isPresent()) {
      return replayOrConflict(byKey.get(), payer, grossMinor, netMinor, currency, lines);
    }

    // 2) Money validation (rule 8: currency-checked integer minor units) + the v1 subsidy guard.
    Money gross = Money.ofMinor(grossMinor, currency);
    Money net = Money.ofMinor(netMinor, currency);
    if (netMinor > grossMinor) {
      throw new PlatformNetExceedsGrossException(grossMinor, netMinor);
    }

    String companyId = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();

    // 3) Advisory lock per (company, PAYER) — the BillWriter/lockPeriod primitive. Serializes
    //    concurrent payouts from the same payer so the loser re-reads the already-decremented
    //    balances instead of double-spending the guard window. Keyed on the payer, not one
    //    channel, because a payout now touches several of its rows at once.
    jdbcTemplate.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        "platform_settlement:" + companyId + ":" + payer);

    // 3a) RE-probe the key now that we hold the lock (ADR 0036 review W2): two concurrent same-key
    //     POSTs both miss the top probe; the loser blocks here until the winner COMMITS, so this
    //     second probe replays instead of re-decrementing and dying on the unique index as a 500.
    Optional<PlatformSettlement> afterLock =
        settlementRepository.findByIdempotencyKey(idempotencyKey);
    if (afterLock.isPresent()) {
      return replayOrConflict(afterLock.get(), payer, grossMinor, netMinor, currency, lines);
    }

    // 4) GUARDED decrement PER LINE: the outstanding_minor >= gross predicate makes an
    //    over-settlement lose atomically (0 rows touched -> 422). Settling is the ONLY
    //    negative-forbidden movement — accruals and clawbacks tolerate negative balances, but a
    //    payout may never take more than a source is owed. RLS scopes the UPDATE (rule 5).
    for (SourceLine line : lines) {
      decrementOutstanding(payer, line, currency, companyId, actor);
    }

    Instant now = clock.instant();
    String period = LedgerPosting.periodOf(now);
    requireConsistentGlCurrency(period, gross);

    List<AllocatedLine> allocated =
        SettlementAllocation.allocate(lines, Math.subtractExact(grossMinor, netMinor));

    UUID entryId = UUID.randomUUID();
    JournalEntry entry = buildSourceSettlementEntry(payer, allocated, net, period, now, entryId);
    persistEntry(entry, companyId);

    PlatformSettlement settlement =
        new PlatformSettlement(payer, gross, net, entryId, now, idempotencyKey);
    settlement.setCompanyId(companyId);
    // saveAndFlush, not save: the line INSERTs below go through JdbcTemplate and bypass the
    // persistence context, so a deferred header INSERT would leave their FK unsatisfied (the same
    // reason GeneralLedgerWriter flushes journal_entry before its journal_line rows).
    settlementRepository.saveAndFlush(settlement);
    insertLines(settlement.getId(), allocated, currency, companyId, actor);
    depositIntoBank(settlement, net, now);

    return new PlatformSettlementResult(settlement, true);
  }

  /**
   * Takes a recorded payout back (ADR 0076): posts a CONTRA entry negating its legs and hands each
   * line's gross back to the sub-ledger it came out of.
   *
   * <p>Append-only is preserved — the settlement is stamped, never deleted or edited, so the ledger
   * keeps both the mistake and its correction. That is the same shape a sale's void takes.
   *
   * <p>Once-only is claimed by a GUARDED update (`WHERE voided_at IS NULL`) rather than a
   * read-then-write: two concurrent voids would otherwise both pass an application check and hand
   * the balance back twice, leaving the sub-ledger permanently above the GL.
   *
   * @throws PlatformSettlementNotFoundException no such payout for this tenant
   * @throws PlatformSettlementAlreadyVoidedException it has already been taken back (409)
   */
  @Transactional
  public PlatformSettlementResult voidSettlement(UUID settlementId) {
    Objects.requireNonNull(settlementId, "settlementId");
    String companyId = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();

    PlatformSettlement settlement =
        settlementRepository
            .findById(settlementId)
            .orElseThrow(() -> new PlatformSettlementNotFoundException(settlementId));

    // Serialize against a concurrent payout FROM THE SAME PAYER, so a void and a settle cannot
    // interleave between the guard and the restore.
    jdbcTemplate.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        "platform_settlement:" + companyId + ":" + settlement.getChannelCode());

    Instant now = clock.instant();
    UUID voidEntryId = UUID.randomUUID();
    // Read before the claim: the guarded UPDATE below bumps `version` behind the managed entity's
    // back, so anything this method still needs from it must be taken first — and the entity must
    // NOT be mutated afterwards or the flush fails an optimistic-lock check it cannot win.
    UUID bankLineId = settlement.getBankLineId();

    // Claim it. Zero rows means someone else already did — never a second contra entry.
    int claimed =
        jdbcTemplate.update(
            """
            UPDATE platform_settlement
               SET voided_at     = ?,
                   void_entry_id = ?,
                   bank_line_id  = NULL,
                   updated_at    = now(),
                   updated_by    = ?,
                   version       = version + 1
             WHERE id = ?
               AND company_id = ?
               AND voided_at IS NULL
            """,
            java.sql.Timestamp.from(now),
            voidEntryId,
            actor,
            settlementId,
            companyId);
    if (claimed == 0) {
      throw new PlatformSettlementAlreadyVoidedException(settlementId);
    }

    String currency = settlement.getCurrency() == null ? null : settlement.getCurrency().strip();
    List<SourceLine> lines = readLinesNormalized(settlementId);
    List<AllocatedLine> allocated = SettlementAllocation.allocate(lines, settlement.getFeeMinor());

    // Hand each line's gross back to the row it was taken from. Restoring is an ADD, mirroring the
    // accrual, so a balance that moved on in the meantime is not overwritten — and it goes through
    // `restore`, not `accumulate`, so it adds the money without re-stating who pays that row: the
    // header names the payer as it was at settle time, which may since have been re-mapped.
    for (AllocatedLine line : allocated) {
      platformReceivable.restore(
          companyId,
          new SettlementSourceReader.SettlementSourceRef(
              line.kind(), line.channelCode(), settlement.getChannelCode()),
          currency,
          line.grossMinor(),
          actor);
    }

    String period = LedgerPosting.periodOf(now);
    // Every other posting path in this service guards this (settle above, AR/AP, ReconciliationWriter)
    // and a contra is a posting like any other: voiding an old IDR payout into a period that has
    // since taken USD entries is exactly the divergence the guard exists to stop.
    requireConsistentGlCurrency(period, Money.ofMinor(settlement.getNetMinor(), currency));
    JournalEntry contra =
        buildVoidEntry(
            settlement.getChannelCode(),
            allocated,
            Money.ofMinor(settlement.getNetMinor(), currency),
            period,
            now,
            voidEntryId);
    persistEntry(contra, companyId);
    reverseBankDeposit(
        bankLineId,
        settlement.getChannelCode(),
        Money.ofMinor(settlement.getNetMinor(), currency),
        period,
        now);

    return new PlatformSettlementResult(settlement, false);
  }

  /**
   * The contra of {@link #buildSourceSettlementEntry} — every leg with its side flipped, so the two
   * entries sum to nothing on every account they touched.
   */
  public JournalEntry buildVoidEntry(
      String payer,
      List<AllocatedLine> allocated,
      Money net,
      String period,
      Instant now,
      UUID entryId) {
    String currency = net.currency().getCurrencyCode();
    List<JournalLine> lines = new ArrayList<>();
    List<AccountRole> rolesPosted = new ArrayList<>();
    int lineNo = 1;

    for (AllocatedLine line : allocated) {
      AccountRole receivableRole = line.kind().accountRole();
      lines.add(
          JournalLine.debit(
              entryId,
              lineNo++,
              requireMapped(receivableRole, now),
              Money.ofMinor(line.grossMinor(), currency)));
      rolesPosted.add(receivableRole);
    }
    if (net.amountMinor() > 0) {
      lines.add(
          JournalLine.credit(
              entryId, lineNo++, requireMapped(AccountRole.CASH_CLEARING, now), net));
      rolesPosted.add(AccountRole.CASH_CLEARING);
    }
    for (AllocatedLine line : allocated) {
      if (line.feeMinor() > 0) {
        AccountRole feeRole = feeRoleFor(line.kind());
        lines.add(
            JournalLine.credit(
                entryId,
                lineNo++,
                requireMapped(feeRole, now),
                Money.ofMinor(line.feeMinor(), currency)));
        rolesPosted.add(feeRole);
      }
    }

    boolean usesIllustrative =
        roleAccountResolver.anyIllustrative(now, rolesPosted.toArray(new AccountRole[0]));
    return JournalEntry.balanced(
        entryId,
        period,
        now,
        "Platform settlement VOID — " + payer,
        currency,
        entryId,
        usesIllustrative,
        lines);
  }

  /**
   * Finishes the money's journey: writes the bank statement line for THIS payout's deposit and
   * reconciles it, so the net lands in BANK instead of sitting in the drawer's account.
   *
   * <p>QRIS and marketplace money never touches the till — the acquirer or platform transfers it to
   * the bank — so leaving the net in CASH_CLEARING made the owner's "cash" figure neither the
   * drawer nor the bank, and left 1000 Bank at zero forever.
   *
   * <p>Reconciliation stays the only writer that debits BANK (ADR 0016): this calls the same {@link
   * ReconciliationWriter} a manual reconcile goes through. The line it creates cannot be reconciled
   * twice, which also closes the double-credit risk of a payout and a manual reconcile both
   * clearing the same transfer.
   *
   * <p>Silently does nothing when there is no ONE obvious account to deposit into — the payout
   * still posts, its net simply waits in CASH_CLEARING to be swept the manual way, exactly as
   * before V68. Booking money is never blocked on a missing setting, and that promise is why the
   * candidate list is filtered rather than merely counted:
   *
   * <ul>
   *   <li><b>Active only.</b> {@code findAllView} returns archived accounts too. One stale account
   *       beside the current one made {@code size() != 1} true and skipped the deposit silently;
   *       a company whose only row is deactivated would have had money booked into a closed
   *       account.
   *   <li><b>Matching currency.</b> {@link StatementLineWriter#importLines} stamps the line with
   *       the ACCOUNT's currency and {@link ReconciliationWriter#reconcile} then posts in it. The
   *       settlement entry was already flushed in this same transaction in the payout's currency,
   *       so a USD account on IDR books threw {@code MismatchedPostingCurrencyException} — and
   *       because this runs inside {@code settleSources}' transaction, it rolled back the ENTIRE
   *       payout. Skipping is the behaviour this javadoc promises; failing the payout is not.
   * </ul>
   */
  private void depositIntoBank(PlatformSettlement settlement, Money net, Instant now) {
    if (net.amountMinor() <= 0) {
      return;
    }
    // getCurrencyCode(), not currency(): Money.currency() is a java.util.Currency, and comparing a
    // String to it compiles happily (String.equals takes Object) while never matching.
    String payoutCurrency = net.currency().getCurrencyCode();
    List<BankAccountView> accounts =
        bankAccountRepository.findAllView().stream()
            .filter(BankAccountView::getActive)
            .filter(a -> a.getCurrency() != null && a.getCurrency().strip().equals(payoutCurrency))
            .toList();
    if (accounts.size() != 1) {
      // Zero: nothing to deposit into. More than one: which account received it is a fact only the
      // merchant knows, and guessing would put money in the wrong place — the request carries it
      // once the console asks (deliberately not inferred here).
      log.warn(
          "platform settlement {} not auto-deposited: {} active {} bank accounts configured",
          settlement.getId(),
          accounts.size(),
          payoutCurrency);
      return;
    }

    UUID bankAccountId = accounts.getFirst().getId();
    List<StatementLineResponse> imported =
        statementLineWriter.importLines(
            bankAccountId,
            List.of(
                new StatementLineInput(
                    // The business date is Asia/Jakarta, the same convention findSummary shifts to.
                    // Dated in UTC, a payout recorded 00:00-07:00 WIB landed on the PREVIOUS day
                    // and would not line up with the real bank statement at reconciliation.
                    LocalDate.ofInstant(now, BUSINESS_ZONE),
                    net.amountMinor(),
                    "Pencairan " + settlement.getChannelCode(),
                    settlement.getIdempotencyKey())));

    UUID lineId = imported.getFirst().id();
    reconciliationWriter.reconcile(lineId, ReconciliationCategory.CLEARING);
    settlement.setBankLineId(lineId);
    settlementRepository.saveAndFlush(settlement);
  }

  /**
   * A replayed key returns the ORIGINAL settlement, but only when it names the same payout. The
   * comparison includes the whole LINE SET, not just the totals: two payouts of the same net from
   * the same payer can still clear different sources, and replaying one as the other would leave
   * the untouched source overstated forever.
   */
  private PlatformSettlementResult replayOrConflict(
      PlatformSettlement existing,
      String payer,
      long grossMinor,
      long netMinor,
      String currency,
      List<SourceLine> lines) {
    boolean samePayload =
        existing.getChannelCode().equals(payer)
            && existing.getGrossMinor() == grossMinor
            && existing.getNetMinor() == netMinor
            && existing.getCurrency() != null
            && existing.getCurrency().strip().equals(currency)
            && readLinesNormalized(existing.getId()).equals(lines);
    if (!samePayload) {
      throw new PlatformSettlementIdempotencyKeyConflictException(
          "Idempotency-Key was already used for a different settlement");
    }
    return new PlatformSettlementResult(existing, false);
  }

  /**
   * Undoes the bank leg of a voided payout — a CONTRA entry ({@code Dr CASH_CLEARING / Cr BANK}),
   * never an un-reconcile. A reconciliation is CORRECTED, not undone: the ledger keeps the deposit
   * and its reversal, which is where the audit trail belongs.
   *
   * <p>The fabricated statement line goes with it. It is not a record from the bank — it is an
   * artefact this payout created — so leaving it behind would show a deposit that, per the void,
   * did not happen, and would sit in the reconciliation report forever.
   */
  private void reverseBankDeposit(
      UUID lineId, String payer, Money net, String period, Instant now) {
    if (lineId == null || net.amountMinor() <= 0) {
      return;
    }
    UUID contraId = UUID.randomUUID();
    List<JournalLine> lines =
        List.of(
            JournalLine.debit(contraId, 1, requireMapped(AccountRole.CASH_CLEARING, now), net),
            JournalLine.credit(contraId, 2, requireMapped(AccountRole.BANK, now), net));
    boolean usesIllustrative =
        roleAccountResolver.anyIllustrative(now, AccountRole.CASH_CLEARING, AccountRole.BANK);
    persistEntry(
        JournalEntry.balanced(
            contraId,
            period,
            now,
            "Platform settlement VOID (bank) — " + payer,
            net.currency().getCurrencyCode(),
            contraId,
            usesIllustrative,
            lines),
        TenantContext.require().companyId());

    jdbcTemplate.update("DELETE FROM bank_statement_line WHERE id = ?", lineId);
  }

  /**
   * Takes one source's gross off its accumulator, or fails the whole payout (422).
   *
   * <p>Scoped to the PAYER as well as the source: without that, a caller could name one payer in
   * the header and drain another's balance — the money would leave the right GL account but the
   * payout would be filed under the wrong payer, and the drained one would read as never having
   * been paid out for as long as it held a balance.
   */
  private void decrementOutstanding(
      String payer, SourceLine line, String currency, String companyId, String actor) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE platform_receivable
               SET outstanding_minor = outstanding_minor - ?,
                   updated_at        = now(),
                   updated_by        = ?,
                   version           = version + 1
             WHERE channel_code = ?
               AND currency = ?
               AND company_id = ?
               AND source_kind = ?
               AND source_code = ?
               AND outstanding_minor >= ?
            """,
            line.grossMinor(),
            actor,
            line.channelCode(),
            currency,
            companyId,
            line.kind().name(),
            payer,
            line.grossMinor());
    if (updated == 0) {
      throw new PlatformOverSettlementException(line.channelCode(), line.grossMinor(), currency);
    }
  }

  /**
   * The payout's lines, in the same normalized order {@link #settleSources} builds them, so the
   * replay comparison is a plain list equality. Named columns, never {@code SELECT *}.
   */
  private List<SourceLine> readLines(UUID settlementId) {
    return jdbcTemplate.query(
        """
        SELECT source_kind, channel_code, gross_minor
          FROM platform_settlement_line
         WHERE settlement_id = ?
        """,
        (rs, rowNum) ->
            new SourceLine(
                SettlementSourceKind.valueOf(rs.getString(1)), rs.getString(2), rs.getLong(3)),
        settlementId);
  }

  /**
   * Ordered the same way {@link #settleSources} orders what it is compared against. Deliberately
   * sorted in Java rather than by the query: Postgres collates punctuation with variable weight, so
   * an `ORDER BY channel_code` and {@code String.compareTo} disagree on codes containing `:` — and
   * a genuine idempotent retry would then 409 instead of replaying.
   */
  private List<SourceLine> readLinesNormalized(UUID settlementId) {
    return SettlementAllocation.normalize(readLines(settlementId));
  }

  private void insertLines(
      UUID settlementId,
      List<AllocatedLine> allocated,
      String currency,
      String companyId,
      String actor) {
    for (AllocatedLine line : allocated) {
      jdbcTemplate.update(
          """
          INSERT INTO platform_settlement_line
              (id, settlement_id, source_kind, channel_code, gross_minor, fee_minor, currency,
               created_at, created_by, updated_at, updated_by, version, company_id)
          VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?, 0, ?)
          """,
          UUID.randomUUID(),
          settlementId,
          line.kind().name(),
          line.channelCode(),
          line.grossMinor(),
          line.feeMinor(),
          currency,
          actor,
          actor,
          companyId);
    }
  }

  /**
   * Every MARKETPLACE channel's outstanding balance (dashboard read; RLS-scoped), alphabetical.
   *
   * <p>Scoped to MARKETPLACE by ADR 0076 phase 1: QRIS and card balances now accrue into the same
   * sub-ledger, but their balances live in DIFFERENT GL accounts (1901 / 1902). Until the
   * multi-source payout of phase 2 credits each row against its own account, offering them to this
   * single-account settle form would credit 1250 for money that is not there.
   */
  @Transactional(readOnly = true)
  public List<PlatformOutstandingResponse> outstanding() {
    return jdbcTemplate.query(
        """
        SELECT r.channel_code, r.currency, r.outstanding_minor
          FROM platform_receivable r
         WHERE r.source_kind = 'MARKETPLACE'
         ORDER BY r.channel_code
        """,
        (rs, rowNum) ->
            new PlatformOutstandingResponse(
                rs.getString(1), rs.getString(2).strip(), rs.getLong(3)));
  }

  /**
   * What each PAYER still owes, with the sources making it up (ADR 0076) — the settlement form's
   * opening question.
   *
   * <p>Grouped by payer rather than by channel because that is how the money arrives: Shopee pays
   * ShopeeFood orders and counter QRIS in one transfer, so they belong in one payout. Rows that net
   * to zero are omitted (nothing to settle); negative rows are kept, because a clawback that leaves
   * a payer owing nothing still has to be visible before the next payout nets it.
   *
   * <p>RLS-scoped automatically; named columns, never {@code SELECT *}.
   */
  @Transactional(readOnly = true)
  public List<PayoutSourceResponse> payoutSources() {
    record Row(String sourceCode, String currency, String kind, String channel, long minor) {}
    List<Row> rows =
        jdbcTemplate.query(
            """
            SELECT source_code, currency, source_kind, channel_code, outstanding_minor
              FROM platform_receivable
             WHERE outstanding_minor <> 0
             ORDER BY source_code, currency, source_kind, channel_code
            """,
            (rs, rowNum) ->
                new Row(
                    rs.getString(1),
                    rs.getString(2).strip(),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getLong(5)));

    List<PayoutSourceResponse> grouped = new ArrayList<>();
    List<PayoutSourceResponse.Line> lines = new ArrayList<>();
    String currentSource = null;
    String currentCurrency = null;
    long total = 0L;
    for (Row row : rows) {
      // Currency is part of the group, not just carried along: platform_receivable is keyed by it,
      // so one payer can hold IDR and USD rows and adding their minor units would be nonsense
      // (rule 8 — every amount travels with its own currency).
      boolean newGroup =
          currentSource == null
              || !currentSource.equals(row.sourceCode())
              || !currentCurrency.equals(row.currency());
      if (newGroup && currentSource != null) {
        grouped.add(
            new PayoutSourceResponse(currentSource, currentCurrency, total, List.copyOf(lines)));
        lines.clear();
        total = 0L;
      }
      currentSource = row.sourceCode();
      currentCurrency = row.currency();
      total = Math.addExact(total, row.minor());
      // Every kind the sub-ledger carries can be cleared here now that card has a fee account
      // (V66). The flag stays on the wire so a future kind can be shown without being selectable.
      lines.add(new PayoutSourceResponse.Line(row.kind(), row.channel(), row.minor(), true));
    }
    if (currentSource != null) {
      grouped.add(
          new PayoutSourceResponse(currentSource, currentCurrency, total, List.copyOf(lines)));
    }
    return List.copyOf(grouped);
  }

  /**
   * The payers whose money has been sitting too long (ADR 0076 phase 3) — what the Beranda nudge
   * shows.
   *
   * <p>A payer is overdue when it still owes something and its LAST payout is older than its
   * cadence, or it has never paid out at all. Age is measured from the last payout rather than from
   * the balance itself: `updated_at` moves on every sale, so a busy channel would look freshly
   * touched forever even if it had never once been settled.
   *
   * <p>A payer spanning several kinds takes the LONGEST cadence among them. Shopee settles its
   * marketplace and QRIS money in one weekly transfer; using the QRIS cadence there would nag every
   * three days about money that is not due yet.
   */
  @Transactional(readOnly = true)
  public List<OverdueSourceResponse> overdueSources() {
    Instant now = clock.instant();
    List<OverdueSourceResponse> overdue = new ArrayList<>();
    for (PayoutSourceResponse source : payoutSources()) {
      if (source.outstandingMinor() <= 0) {
        continue; // nothing owed, or a clawback the next payout nets — not a nudge.
      }
      int cadenceDays =
          source.lines().stream()
              .map(l -> SettlementSourceKind.valueOf(l.sourceKind()))
              .mapToInt(SettlementSourceKind::defaultCadenceDays)
              .max()
              .orElse(SettlementSourceKind.MARKETPLACE.defaultCadenceDays());

      // Nudging about a balance this flow cannot clear is a dead end: card has no mapped fee
      // account, so its payer would be flagged forever with a form that has nothing to tick.
      long settleable =
          source.lines().stream()
              .filter(PayoutSourceResponse.Line::settleable)
              .mapToLong(PayoutSourceResponse.Line::outstandingMinor)
              .sum();
      if (settleable <= 0) {
        continue;
      }

      Instant lastPaidAt = lastPayoutAt(source);
      Long daysSince = lastPaidAt == null ? null : Duration.between(lastPaidAt, now).toDays();
      if (daysSince != null && daysSince < cadenceDays) {
        continue;
      }
      overdue.add(
          new OverdueSourceResponse(
              source.sourceCode(),
              source.currency(),
              source.outstandingMinor(),
              daysSince,
              cadenceDays));
    }
    return List.copyOf(overdue);
  }

  /**
   * When this payer last paid out, or {@code null} if it never has.
   *
   * <p>Voided payouts are excluded: taking one back returns the balance, so counting it here would
   * suppress the Beranda nudge for a whole cadence window (up to a week for Shopee) over money that
   * was never actually paid out.
   *
   * <p>Found through the payout's LINES rather than the header's payer name. A settlement header
   * records the payer as it was named at the time, but naming an acquirer re-points a balance from
   * (say) `QRIS` to `SHOPEE` — matching on the header alone would make a payer that settled
   * yesterday read as "never paid out" the moment it was configured. The line's channel_code is
   * stable across that move, so it is the durable link. Named columns, no {@code SELECT *}.
   */
  private Instant lastPayoutAt(PayoutSourceResponse source) {
    List<String> channels =
        source.lines().stream().map(PayoutSourceResponse.Line::channelCode).toList();
    if (channels.isEmpty()) {
      return null;
    }
    String placeholders = String.join(", ", java.util.Collections.nCopies(channels.size(), "?"));
    List<Instant> found =
        jdbcTemplate.query(
            """
            SELECT MAX(s.settled_at)
              FROM platform_settlement s
              JOIN platform_settlement_line l ON l.settlement_id = s.id
             WHERE l.channel_code IN (%s)
               AND s.voided_at IS NULL
            """
                .formatted(placeholders),
            (rs, rowNum) -> {
              java.sql.Timestamp ts = rs.getTimestamp(1);
              return ts == null ? null : ts.toInstant();
            },
            channels.toArray());
    return found.isEmpty() ? null : found.getFirst();
  }

  /** Settlement history, optionally filtered by channel, most recent first (capped at 100). */
  @Transactional(readOnly = true)
  public List<PlatformSettlementResponse> history(String channelCode) {
    return settlementRepository.findHistoryViews(channelCode).stream()
        .map(
            v ->
                new PlatformSettlementResponse(
                    v.getId(),
                    v.getChannelCode(),
                    v.getGrossMinor(),
                    v.getNetMinor(),
                    v.getFeeMinor(),
                    v.getCurrency() == null ? null : v.getCurrency().strip(),
                    v.getSettledAt(),
                    v.getVoidedAt() != null))
        .toList();
  }

  /**
   * The per-channel settlement summary for a {@code YYYY-MM} period ({@code GET
   * /api/v1/platform-settlements/summary}) — one row per {@code (channelCode, currency)} bucket:
   * settled gross/fee/net totals and settlement count. RLS-scoped automatically; no manual {@code
   * company_id} predicate. Read path: a native-query projection, never {@code SELECT *}.
   */
  @Transactional(readOnly = true)
  public List<PlatformSettlementSummaryResponse> summary(String period) {
    return settlementRepository.findSummary(period).stream()
        .map(PlatformSettlementWriter::toSummaryResponse)
        .toList();
  }

  /** Maps a read projection to the response shape (currency CHAR(3) is right-padded — strip it). */
  private static PlatformSettlementSummaryResponse toSummaryResponse(
      PlatformSettlementSummaryView view) {
    return new PlatformSettlementSummaryResponse(
        view.getChannelCode(),
        view.getSettledGrossMinor(),
        view.getFeeMinor(),
        view.getNetMinor(),
        view.getSettlementCount(),
        view.getCurrency() == null ? null : view.getCurrency().strip());
  }

  /**
   * Builds (but does not persist) the balanced entry for a SINGLE-source payout — the ADR 0036
   * shape, kept because a unit test asserts its exact legs. Delegates to {@link
   * #buildSourceSettlementEntry} so the single- and multi-source journals can never diverge.
   */
  public JournalEntry buildSettlementEntry(
      String channelCode, Money gross, Money net, String period, Instant now, UUID entryId) {
    long feeMinor = Math.subtractExact(gross.amountMinor(), net.amountMinor());
    return buildSourceSettlementEntry(
        channelCode,
        List.of(
            new AllocatedLine(
                SettlementSourceKind.MARKETPLACE, channelCode, gross.amountMinor(), feeMinor)),
        net,
        period,
        now,
        entryId);
  }

  /**
   * The balanced entry for one payout, whatever it cleared (ADR 0076 phase 2):
   *
   * <pre>Dr CASH_CLEARING (net)
   * Dr &lt;fee account per line&gt; (that line's share of the deduction)
   * Cr &lt;receivable account per line&gt; (that line's gross)</pre>
   *
   * <p>The credit follows each line's OWN kind, which is the whole point: a Shopee payout credits
   * 1250 for its ShopeeFood gross and 1901 for its QRIS gross, in one entry. The debit stays
   * CASH_CLEARING — bank reconciliation remains the only Dr-BANK writer (ADR 0016).
   *
   * <p>Public + pure (no DB beyond the role-map lookups) so a unit test can assert the exact legs.
   * Zero-amount legs are omitted: a fee-free payout posts no fee leg, and a payout whose gross was
   * entirely eaten by fees posts no clearing leg.
   */
  public JournalEntry buildSourceSettlementEntry(
      String payer,
      List<AllocatedLine> allocated,
      Money net,
      String period,
      Instant now,
      UUID entryId) {
    String currency = net.currency().getCurrencyCode();
    List<JournalLine> lines = new ArrayList<>();
    List<AccountRole> rolesPosted = new ArrayList<>();
    int lineNo = 1;

    if (net.amountMinor() > 0) {
      lines.add(
          JournalLine.debit(entryId, lineNo++, requireMapped(AccountRole.CASH_CLEARING, now), net));
      rolesPosted.add(AccountRole.CASH_CLEARING);
    }
    for (AllocatedLine line : allocated) {
      if (line.feeMinor() > 0) {
        AccountRole feeRole = feeRoleFor(line.kind());
        lines.add(
            JournalLine.debit(
                entryId,
                lineNo++,
                requireMapped(feeRole, now),
                Money.ofMinor(line.feeMinor(), currency)));
        rolesPosted.add(feeRole);
      }
    }
    for (AllocatedLine line : allocated) {
      AccountRole receivableRole = line.kind().accountRole();
      lines.add(
          JournalLine.credit(
              entryId,
              lineNo++,
              requireMapped(receivableRole, now),
              Money.ofMinor(line.grossMinor(), currency)));
      rolesPosted.add(receivableRole);
    }

    // Derived from the provenance of the roles actually posted above (the conditional legs only
    // count when present), rather than hardcoded.
    boolean usesIllustrative =
        roleAccountResolver.anyIllustrative(now, rolesPosted.toArray(new AccountRole[0]));
    return JournalEntry.balanced(
        entryId,
        period,
        now,
        "Platform settlement — " + payer,
        currency,
        entryId,
        usesIllustrative,
        lines);
  }

  /**
   * The expense account a source's deduction belongs to. Keeping these apart is what stops a Shopee
   * payout from charging its whole MDR to "marketplace fee" — 5720 would quietly stop meaning
   * anything, and nothing in the books would look wrong.
   */
  private static AccountRole feeRoleFor(SettlementSourceKind kind) {
    return switch (kind) {
      case MARKETPLACE -> AccountRole.PLATFORM_FEE_EXPENSE;
      case QRIS -> AccountRole.QRIS_FEE_EXPENSE;
      case CARD -> AccountRole.CARD_FEE_EXPENSE;
    };
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
    generalLedgerWriter.post(entry, companyId);
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
