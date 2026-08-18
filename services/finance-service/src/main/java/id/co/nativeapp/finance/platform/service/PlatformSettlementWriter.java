package id.co.nativeapp.finance.platform.service;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.platform.domain.PlatformNetExceedsGrossException;
import id.co.nativeapp.finance.platform.domain.PlatformOverSettlementException;
import id.co.nativeapp.finance.platform.domain.PlatformSettlement;
import id.co.nativeapp.finance.platform.domain.PlatformSettlementIdempotencyKeyConflictException;
import id.co.nativeapp.finance.platform.dto.PlatformOutstandingResponse;
import id.co.nativeapp.finance.platform.dto.PlatformSettlementResponse;
import id.co.nativeapp.finance.platform.dto.PlatformSettlementResult;
import id.co.nativeapp.finance.platform.repository.PlatformSettlementRepository;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
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

  private final PlatformSettlementRepository settlementRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final RoleAccountResolver roleAccountResolver;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public PlatformSettlementWriter(
      PlatformSettlementRepository settlementRepository,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      RoleAccountResolver roleAccountResolver,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.settlementRepository = settlementRepository;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.roleAccountResolver = roleAccountResolver;
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  /**
   * Records one payout of {@code gross} settled / {@code net} received for {@code channelCode}.
   *
   * @throws PlatformSettlementIdempotencyKeyConflictException replayed key, different payload (409)
   * @throws PlatformNetExceedsGrossException net &gt; gross — negative commission (422)
   * @throws PlatformOverSettlementException the channel's outstanding does not cover gross (422)
   */
  @Transactional
  public PlatformSettlementResult settle(
      String channelCode, long grossMinor, long netMinor, String currency, String idempotencyKey) {
    Objects.requireNonNull(channelCode, "channelCode");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    // Review S1: restaurant stores channel codes uppercase — normalize so a lowercase request
    // matches the accumulator row instead of masquerading as an over-settlement.
    channelCode = channelCode.strip().toUpperCase(java.util.Locale.ROOT);

    // 1) Replay-by-key probe FIRST (PayrollSettlementWriter idiom): a genuine retry never re-runs
    //    the decrement — the original attempt already moved the money.
    Optional<PlatformSettlement> byKey = settlementRepository.findByIdempotencyKey(idempotencyKey);
    if (byKey.isPresent()) {
      PlatformSettlement existing = byKey.get();
      boolean samePayload =
          existing.getChannelCode().equals(channelCode)
              && existing.getGrossMinor() == grossMinor
              && existing.getNetMinor() == netMinor
              && existing.getCurrency() != null
              && existing.getCurrency().strip().equals(currency);
      if (!samePayload) {
        throw new PlatformSettlementIdempotencyKeyConflictException(
            "Idempotency-Key was already used for a different settlement");
      }
      return new PlatformSettlementResult(existing, false);
    }

    // 2) Money validation (rule 8: currency-checked integer minor units) + the v1 subsidy guard.
    Money gross = Money.ofMinor(grossMinor, currency);
    Money net = Money.ofMinor(netMinor, currency);
    if (netMinor > grossMinor) {
      throw new PlatformNetExceedsGrossException(grossMinor, netMinor);
    }

    String companyId = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();

    // 3) Advisory lock per (company, channel) — the BillWriter/lockPeriod primitive. Serializes
    //    concurrent settlements of the SAME channel so the loser re-reads the already-decremented
    //    balance instead of double-spending the guard window.
    jdbcTemplate.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        "platform_settlement:" + companyId + ":" + channelCode);

    // 3a) Review W2: RE-probe the key now that we hold the lock. Two concurrent same-key POSTs
    // both miss the top probe; the loser blocks here until the winner COMMITS (xact lock),
    // so this second probe sees the winner's row and replays 200 — without it the loser
    // re-decrements and dies on uq_platform_settlement_idem as an opaque 500.
    Optional<PlatformSettlement> afterLock =
        settlementRepository.findByIdempotencyKey(idempotencyKey);
    if (afterLock.isPresent()) {
      return new PlatformSettlementResult(afterLock.get(), false);
    }

    // 4) GUARDED single-statement decrement (the OrgUnitRefWriter JdbcTemplate idiom — no entity
    //    exists for the accumulator row): the outstanding_minor >= gross predicate makes an
    //    over-settlement lose atomically (0 rows, nothing touched → 422). Settling is the ONLY
    //    negative-forbidden movement — accrual/clawback upserts tolerate negative balances, but a
    //    settlement may never take more than is outstanding. RLS scopes the UPDATE (rule 5).
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
               AND outstanding_minor >= ?
            """,
            grossMinor,
            actor,
            channelCode,
            currency,
            companyId,
            grossMinor);
    if (updated == 0) {
      throw new PlatformOverSettlementException(channelCode, grossMinor, currency);
    }

    Instant now = clock.instant();
    String period = LedgerPosting.periodOf(now);
    requireConsistentGlCurrency(period, gross);

    UUID entryId = UUID.randomUUID();
    JournalEntry entry = buildSettlementEntry(channelCode, gross, net, period, now, entryId);
    persistEntry(entry, companyId);

    PlatformSettlement settlement =
        new PlatformSettlement(channelCode, gross, net, entryId, now, idempotencyKey);
    settlement.setCompanyId(companyId);
    settlementRepository.save(settlement);

    return new PlatformSettlementResult(settlement, true);
  }

  /** Every channel's outstanding balance (dashboard read; RLS-scoped), alphabetical. */
  @Transactional(readOnly = true)
  public List<PlatformOutstandingResponse> outstanding() {
    return jdbcTemplate.query(
        """
        SELECT r.channel_code, r.currency, r.outstanding_minor
          FROM platform_receivable r
         ORDER BY r.channel_code
        """,
        (rs, rowNum) ->
            new PlatformOutstandingResponse(
                rs.getString(1), rs.getString(2).strip(), rs.getLong(3)));
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
                    v.getSettledAt()))
        .toList();
  }

  /**
   * Builds (but does not persist) the balanced settlement entry — public + pure (no DB beyond the
   * role resolver lookups) so a unit test can assert the exact legs, mirroring {@code
   * PayrollSettlementWriter#buildSettlementEntry}. Zero-amount legs are omitted. {@code
   * source_event_id = entryId} (its own id, UNIQUE backstop).
   */
  public JournalEntry buildSettlementEntry(
      String channelCode, Money gross, Money net, String period, Instant now, UUID entryId) {
    Money fee =
        Money.ofMinor(
            Math.subtractExact(gross.amountMinor(), net.amountMinor()),
            gross.currency().getCurrencyCode());
    List<JournalLine> lines = new ArrayList<>();
    List<AccountRole> rolesPosted = new ArrayList<>();
    int lineNo = 1;
    if (net.amountMinor() > 0) {
      lines.add(
          JournalLine.debit(entryId, lineNo++, requireMapped(AccountRole.CASH_CLEARING, now), net));
      rolesPosted.add(AccountRole.CASH_CLEARING);
    }
    if (fee.amountMinor() > 0) {
      lines.add(
          JournalLine.debit(
              entryId, lineNo++, requireMapped(AccountRole.PLATFORM_FEE_EXPENSE, now), fee));
      rolesPosted.add(AccountRole.PLATFORM_FEE_EXPENSE);
    }
    lines.add(
        JournalLine.credit(
            entryId, lineNo, requireMapped(AccountRole.PLATFORM_RECEIVABLE, now), gross));
    rolesPosted.add(AccountRole.PLATFORM_RECEIVABLE);
    // Derived from the provenance of the roles actually posted above (the conditional
    // CASH_CLEARING/PLATFORM_FEE_EXPENSE legs only count when present), rather than hardcoded.
    boolean usesIllustrative =
        roleAccountResolver.anyIllustrative(now, rolesPosted.toArray(new AccountRole[0]));
    return JournalEntry.balanced(
        entryId,
        period,
        now,
        "Platform settlement — " + channelCode,
        gross.currency().getCurrencyCode(),
        entryId,
        usesIllustrative,
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
