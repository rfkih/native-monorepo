package id.co.nativeapp.restaurant.register.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.register.domain.RegisterCloseCorrectionNotAllowedException;
import id.co.nativeapp.restaurant.register.domain.RegisterSession;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionIdempotencyKeyConflictException;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionNotClosedException;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionNotFoundException;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionNotOpenException;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionTender;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.ClosedSessionSummaryResponse;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionResult;
import id.co.nativeapp.restaurant.register.dto.RegisterExpectedResponse;
import id.co.nativeapp.restaurant.register.dto.RegisterSessionResponse;
import id.co.nativeapp.restaurant.register.dto.RegisterSummaryResponse;
import id.co.nativeapp.restaurant.register.dto.TenderCount;
import id.co.nativeapp.restaurant.register.dto.TenderExpected;
import id.co.nativeapp.restaurant.register.dto.TenderSalesLine;
import id.co.nativeapp.restaurant.register.messaging.RegisterSessionClosedSchema;
import id.co.nativeapp.restaurant.register.projection.ClosedSessionSalesView;
import id.co.nativeapp.restaurant.register.projection.RegisterSessionView;
import id.co.nativeapp.restaurant.register.projection.SaleSummaryView;
import id.co.nativeapp.restaurant.register.repository.RegisterSessionRepository;
import id.co.nativeapp.restaurant.register.repository.RegisterSessionTenderRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for register sessions (ADR 0036, closing kasir). A
 * distinct bean from {@link RegisterSessionService} so the Spring proxy applies the transaction +
 * RLS-GUC advice (the SaleWriter pattern).
 *
 * <p><strong>Close is the money path.</strong> Under a pessimistic row lock it computes the
 * expected drawer server-side ({@code openingFloat + Σ cash sales in [openedAt, now) − Σ cash
 * refunds in the window}), derives the SIGNED variance with overflow-safe arithmetic, transitions
 * OPEN→CLOSED one-shot, and writes the {@code RegisterSessionClosed} outbox row IN THE SAME
 * transaction (rule 3) — finance posts only that variance.
 *
 * <p><strong>The {@code CashWindowLock} contract (verified HIGH race fix).</strong> {@link #close}
 * previously computed {@code closeInstant = Instant.now()} and summed {@code sale} / {@code
 * payment_refund} / {@code gift_card_sale} rows in {@code [openedAt, closeInstant)} with no
 * serialization against concurrent sale/refund commits — a not-yet-committed row with {@code
 * occurred_at < closeInstant} was invisible to the SUM, and (since the NEXT session's window starts
 * only at its own {@code openedAt >= closeInstant}) could never be counted by any session again.
 * The fix is a per-business READERS-WRITER {@link CashWindowLock}, not a plain mutex (a plain mutex
 * was tried first and rejected — it also serialized two UNRELATED concurrent sales against EACH
 * OTHER, which broke a loyalty/gift-card balance-decrement race test that requires genuine
 * concurrency): {@link #close} (and {@link #open}, defensively, for the analogous open-boundary
 * case) acquire the EXCLUSIVE mode ({@link CashWindowLock#acquireForClose}); EVERY
 * sale/refund-committing transaction ({@code SaleWriter.create}, {@code OrderWriter.checkout},
 * {@code OrderWriter.payParked}, {@code BillWriter.payBill}, {@code PaymentCaptureWriter.capture},
 * {@code VoidRefundWriter.refund}) acquires the SHARED mode ({@link
 * CashWindowLock#acquireForCommit}) — SHARED holders never block each other, only EXCLUSIVE. Both
 * modes are acquired as the FIRST lock-acquiring statement in their transaction, strictly BEFORE
 * capturing the timestamp that will become {@code closeInstant} (here) or the row's {@code
 * occurred_at} (there). Ordering is deadlock-safe: {@link #close} takes the pessimistic {@code
 * findWithLockById} row lock BEFORE the advisory lock, but no OTHER transaction ever acquires that
 * same row lock (only {@code close} does, and the idempotency/status checks reject a second
 * concurrent close on the same session before it would reach either lock), so the two locks never
 * form a cross-transaction cycle. See {@link CashWindowLock} class javadoc for the full
 * before/after reasoning.
 */
@Component
public class RegisterSessionWriter {

  /**
   * v1 default zone for deriving {@code business_date} when the request omits it — Indonesian SMEs
   * (documented simplification, ADR 0036; a per-outlet timezone is the additive follow-up).
   */
  private static final ZoneId DEFAULT_BUSINESS_ZONE = ZoneId.of("Asia/Jakarta");

  /**
   * Cap on the past closed-day history browse — 90 SESSIONS (not days: a multi-shift outlet closes
   * more than once per day, so this is ~45 calendar days at two shifts). Bounded, index-backed,
   * newest first.
   */
  private static final int CLOSED_HISTORY_LIMIT = 90;

  /**
   * How recent a close must be to self-correct in-app (ADR 0064, recent/unsealed-only). A coarse
   * proxy for "the accounting period is still open" — restaurant-service cannot see finance's seal
   * across the DB boundary, so finance is the authority (it quarantines a correction to a sealed
   * period); this bound just stops ancient edits. ~2 months covers correcting last month's close.
   */
  private static final Duration CORRECTION_MAX_AGE = Duration.ofDays(62);

  private final RegisterSessionRepository repository;
  private final RegisterSessionTenderRepository tenderRepository;
  private final OutboxWriter outboxWriter;
  private final OutletAccessGuard outletAccessGuard;
  private final CashWindowLock cashWindowLock;

  public RegisterSessionWriter(
      RegisterSessionRepository repository,
      RegisterSessionTenderRepository tenderRepository,
      OutboxWriter outboxWriter,
      OutletAccessGuard outletAccessGuard,
      CashWindowLock cashWindowLock) {
    this.repository = repository;
    this.tenderRepository = tenderRepository;
    this.outboxWriter = outboxWriter;
    this.outletAccessGuard = outletAccessGuard;
    this.cashWindowLock = cashWindowLock;
  }

  /**
   * Opens a drawer session. The replay probe runs first (same {@code Idempotency-Key} → the
   * existing session, no second row); the {@code uq_crs_one_open_per_outlet} partial unique
   * backstops the double-open race — the service layer maps that conflict to a 409.
   */
  @Transactional
  public OpenSessionResult open(OpenSessionRequest request, String idempotencyKey) {
    String companyId = TenantContext.require().companyId();

    Optional<RegisterSessionView> replay = repository.findViewByOpenIdempotencyKey(idempotencyKey);
    if (replay.isPresent()) {
      RegisterSessionView existing = replay.get();
      // Review W2/W3: a replay must be the SAME logical request — same outlet, float, currency.
      // A reused key with a different payload is a client bug surfaced as 409, never a silent 200
      // with another outlet's session.
      long requestedFloat = request.openingFloatMinor() == null ? 0L : request.openingFloatMinor();
      boolean samePayload =
          existing.getBusinessId().equals(request.businessId())
              && existing.getOpeningFloatMinor() == requestedFloat
              && existing.getCurrency() != null
              && existing.getCurrency().strip().equals(request.currency());
      if (!samePayload) {
        throw new RegisterSessionIdempotencyKeyConflictException(
            "Idempotency-Key was already used to open a different session");
      }
      // The replay still answers with session data — same outlet gate as the original open (W1).
      outletAccessGuard.enforce(existing.getBusinessId());
      return new OpenSessionResult(toResponse(existing), false);
    }

    outletAccessGuard.enforce(request.businessId());

    long floatMinor = request.openingFloatMinor() == null ? 0L : request.openingFloatMinor();
    // Money.ofMinor validates the ISO-4217 code (rule 8); the float itself may legitimately be 0.
    Money.ofMinor(floatMinor, request.currency());
    if (floatMinor < 0) {
      throw new IllegalArgumentException("openingFloatMinor must be >= 0");
    }

    // CashWindowLock — EXCLUSIVE, FIRST lock-acquiring statement, before the openedAt timestamp
    // (defensive symmetry with close(); see class javadoc). Nothing above takes a DB lock (plain
    // reads / in-memory validation only).
    cashWindowLock.acquireForClose(request.businessId());

    Instant now = Instant.now();
    LocalDate businessDate =
        request.businessDate() != null
            ? request.businessDate()
            : LocalDate.ofInstant(now, DEFAULT_BUSINESS_ZONE);

    RegisterSession session =
        RegisterSession.open(
            request.businessId(),
            businessDate,
            now,
            floatMinor,
            request.currency(),
            idempotencyKey);
    session.setCompanyId(companyId);
    repository.saveAndFlush(session);
    return new OpenSessionResult(RegisterSessionResponse.from(session), true);
  }

  /**
   * Closes a session with the cashier's physical count. Same-key replay → the already-CLOSED
   * session (200); a different key against a non-OPEN session → {@link
   * RegisterSessionNotOpenException} (409). The {@code RegisterSessionClosed} outbox row commits
   * atomically with the CLOSED transition.
   */
  @Transactional
  public RegisterSessionResponse close(
      UUID sessionId, CloseSessionRequest request, String idempotencyKey) {
    String companyId = TenantContext.require().companyId();

    Optional<RegisterSessionView> replay = repository.findViewByCloseIdempotencyKey(idempotencyKey);
    if (replay.isPresent()) {
      RegisterSessionView existing = replay.get();
      // Review W3: same key must mean the same logical close (same session, same cash count) — AND
      // the same non-cash tender counts (ADR 0038 phase 2, review W2), so a reused key with a
      // different payload is a 409, never a silent 200 with the original reconciliation.
      Long replayCounted = existing.getCountedCashMinor();
      if (!existing.getId().equals(sessionId)
          || replayCounted == null
          || !replayCounted.equals(request.countedCashMinor())
          || !sameTenderCounts(existing.getId(), request.tenderCounts())) {
        throw new RegisterSessionIdempotencyKeyConflictException(
            "Idempotency-Key was already used to close with a different request");
      }
      // The replay still answers with session data — same outlet gate as the live close (W1).
      outletAccessGuard.enforce(existing.getBusinessId());
      return toResponse(existing);
    }

    RegisterSession session =
        repository
            .findWithLockById(sessionId)
            .orElseThrow(() -> new RegisterSessionNotFoundException(sessionId));
    // Review W1: closing another outlet's drawer needs the same outlet-assignment gate as opening
    // it — RLS scopes the company, not the outlet.
    outletAccessGuard.enforce(session.getBusinessId());
    if (!RegisterSession.STATUS_OPEN.equals(session.getStatus())) {
      throw new RegisterSessionNotOpenException(sessionId, session.getStatus());
    }
    long countedCash = request.countedCashMinor(); // @NotNull @PositiveOrZero at the edge

    // CashWindowLock — EXCLUSIVE, BEFORE closeInstant is captured and the cash sums run (verified
    // HIGH race fix; see class javadoc + CashWindowLock javadoc). This is the second lock this
    // transaction takes (after the findWithLockById row lock above) but the FIRST advisory/
    // business-scoped lock; no other transaction ever takes findWithLockById on
    // cash_register_session, so the two never form a cross-transaction cycle (documented ordering
    // — see class javadoc). Waits for every in-flight sale/refund's SHARED lock to release.
    cashWindowLock.acquireForClose(session.getBusinessId());

    Instant closeInstant = Instant.now();
    // Cash INTO the drawer = cash-collected sale portions + cash gift-card sales (a gift card
    // sold for cash is drawer money even though it is a liability, not revenue — ADR 0036).
    long cashSales =
        Math.addExact(
            repository.sumCashSales(session.getBusinessId(), session.getOpenedAt(), closeInstant),
            repository.sumCashGiftCardSales(
                session.getBusinessId(), session.getOpenedAt(), closeInstant));
    long cashRefunds =
        repository.sumCashRefunds(session.getBusinessId(), session.getOpenedAt(), closeInstant);
    // expected = float + sales − refunds; overShort = counted − expected. Overflow-safe (the
    // AssetDisposalWriter precedent) — a poisoned sum must throw, never wrap.
    long expected =
        Math.subtractExact(Math.addExact(session.getOpeningFloatMinor(), cashSales), cashRefunds);
    long overShort = Math.subtractExact(countedCash, expected);

    session.close(
        closeInstant, cashSales, cashRefunds, expected, countedCash, overShort, idempotencyKey);
    repository.saveAndFlush(session);

    // Non-cash per-tender reconciliation (ADR 0038 phase 2): for each tender the cashier counted,
    // persist a line and carry it on the event so finance trues that tender's clearing account.
    List<RegisterSessionClosedSchema.TenderLine> tenderLines =
        reconcileTenders(session, companyId, closeInstant, request.tenderCounts());

    GenericRecord event =
        RegisterSessionClosedSchema.toRecord(
            session.getId(),
            companyId,
            session.getBusinessId(),
            session.getOpenedAt(),
            closeInstant,
            session.getOpeningFloatMinor(),
            cashSales,
            cashRefunds,
            expected,
            countedCash,
            overShort,
            session.getCurrency(),
            tenderLines,
            null, // supersedes_event_id — an original close supersedes nothing (ADR 0064)
            1, // close_seq
            null); // reason
    UUID closeEventId =
        outboxWriter.write(
            RegisterSessionClosedSchema.AGGREGATE_TYPE,
            session.getId().toString(),
            RegisterSessionClosedSchema.EVENT_TYPE,
            AvroSerde.serialize(event),
            null,
            UUID.fromString(companyId),
            closeInstant);
    // ADR 0064: remember the event id finance keys its variance journal on, so a later
    // manager/owner
    // correction can name it as the entry to reverse. Persists in this same close transaction.
    session.recordCloseEventId(closeEventId);

    return RegisterSessionResponse.from(session);
  }

  /**
   * Manager/owner CASH-count CORRECTION of an already-CLOSED session (ADR 0064) — the fix for a
   * cashier who closed with the wrong counted cash. Re-emits the close snapshot with the corrected
   * counted cash + recomputed over/short, MARKED as superseding the prior close/correction event so
   * finance reverses that variance journal and posts the corrected one in its place (append-only,
   * books stay balanced). Everything else on the event is UNCHANGED — the window, cash
   * sales/refunds, expected cash, and the per-tender lines (only the physical cash count was wrong)
   * — so the tender legs reverse-and-re-post identically and only the cash variance moves. The
   * session stays CLOSED; its counted/over-short are amended in place (the prior values live on in
   * the CDC audit trail).
   *
   * <p>Guards: CLOSED only (409); recent + reversible only (422 when older than {@link
   * #CORRECTION_MAX_AGE} or closed before this feature, i.e. no {@code close_event_id}); {@code
   * countedCash >= 0}. A finance-sealed period is caught downstream (the consumer quarantines the
   * correction to the accountant). Idempotent: correcting to the value already recorded is a no-op.
   */
  @Transactional
  public RegisterSessionResponse correctClose(UUID sessionId, long newCountedCash, String reason) {
    String companyId = TenantContext.require().companyId();

    RegisterSession session =
        repository
            .findWithLockById(sessionId)
            .orElseThrow(() -> new RegisterSessionNotFoundException(sessionId));
    outletAccessGuard.enforce(session.getBusinessId());

    if (!RegisterSession.STATUS_CLOSED.equals(session.getStatus())) {
      throw new RegisterSessionNotClosedException(sessionId, session.getStatus());
    }
    if (newCountedCash < 0) {
      throw new IllegalArgumentException("countedCashMinor must be >= 0");
    }
    UUID priorEventId = session.getCloseEventId();
    if (priorEventId == null) {
      throw new RegisterCloseCorrectionNotAllowedException(
          "register session "
              + sessionId
              + " was closed before corrections were supported (no reversible event) — post an"
              + " adjusting entry instead");
    }
    Instant closedAt = session.getClosedAt();
    if (closedAt.isBefore(Instant.now().minus(CORRECTION_MAX_AGE))) {
      throw new RegisterCloseCorrectionNotAllowedException(
          "register session "
              + sessionId
              + " was closed more than "
              + CORRECTION_MAX_AGE.toDays()
              + " days ago — too old to self-correct; post an adjusting entry instead");
    }

    // Idempotent no-op: the counted cash already equals the requested figure (double-submit/retry).
    long currentCounted =
        session.getCountedCashMinor() == null ? 0L : session.getCountedCashMinor();
    if (currentCounted == newCountedCash) {
      return RegisterSessionResponse.from(session);
    }

    // Over/short is re-derived from the STORED expected — the window's cash sales/refunds are
    // historical; only the physical count moved.
    long expected = session.getExpectedCashMinor() == null ? 0L : session.getExpectedCashMinor();
    long newOverShort = Math.subtractExact(newCountedCash, expected);

    // Re-emit the per-tender lines UNCHANGED (this is a cash-only correction): finance reverses the
    // whole prior entry and re-posts the whole corrected entry, so the tender legs net to zero and
    // only the cash variance changes.
    List<RegisterSessionClosedSchema.TenderLine> tenderLines =
        tenderRepository.findBySessionId(sessionId).stream()
            .map(
                t ->
                    new RegisterSessionClosedSchema.TenderLine(
                        t.getTenderType(),
                        t.getExpectedMinor(),
                        t.getCountedMinor(),
                        t.getOverShortMinor()))
            .toList();

    GenericRecord event =
        RegisterSessionClosedSchema.toRecord(
            session.getId(),
            companyId,
            session.getBusinessId(),
            session.getOpenedAt(),
            closedAt,
            session.getOpeningFloatMinor(),
            session.getCashSalesMinor() == null ? 0L : session.getCashSalesMinor(),
            session.getCashRefundsMinor() == null ? 0L : session.getCashRefundsMinor(),
            expected,
            newCountedCash,
            newOverShort,
            session.getCurrency(),
            tenderLines,
            priorEventId,
            session.getCloseSeq() + 1,
            reason);
    UUID correctionEventId =
        outboxWriter.write(
            RegisterSessionClosedSchema.AGGREGATE_TYPE,
            session.getId().toString(),
            RegisterSessionClosedSchema.EVENT_TYPE,
            AvroSerde.serialize(event),
            null,
            UUID.fromString(companyId),
            closedAt);

    session.correctCash(newCountedCash, newOverShort, correctionEventId);
    return RegisterSessionResponse.from(session);
  }

  /**
   * True when the persisted non-cash tender counts for a session match the request's — the
   * close-replay payload check (ADR 0038 phase 2, review W2). Maps built by last-wins so a
   * malformed duplicate-tender request never throws here (the live path rejects duplicates
   * separately).
   */
  private boolean sameTenderCounts(UUID sessionId, List<TenderCount> requested) {
    Map<String, Long> persisted = new HashMap<>();
    for (RegisterSessionTender tender : tenderRepository.findBySessionId(sessionId)) {
      persisted.put(tender.getTenderType(), tender.getCountedMinor());
    }
    Map<String, Long> asked = new HashMap<>();
    for (TenderCount count : requested) {
      asked.put(count.tenderType(), count.countedMinor());
    }
    return persisted.equals(asked);
  }

  /**
   * Computes + persists the NON-cash per-tender reconciliation for a close (ADR 0038 phase 2) and
   * returns the lines to carry on the event. For each counted tender, {@code expected = Σ sales − Σ
   * refunds} over the SAME closed window {@code [openedAt, closeInstant)} — so it matches what
   * accrued in that tender's clearing account — and {@code overShort = counted − expected}
   * (overflow-safe). A duplicate tender in the request is a client bug (→ 400).
   */
  private List<RegisterSessionClosedSchema.TenderLine> reconcileTenders(
      RegisterSession session, String companyId, Instant closeInstant, List<TenderCount> counts) {
    List<RegisterSessionClosedSchema.TenderLine> lines = new ArrayList<>(counts.size());
    Set<String> seen = new LinkedHashSet<>();
    for (TenderCount count : counts) {
      if (!seen.add(count.tenderType())) {
        throw new IllegalArgumentException(
            "duplicate tender in close request: " + count.tenderType());
      }
      long tenderExpected =
          Math.subtractExact(
              repository.sumSalesByTender(
                  session.getBusinessId(), count.tenderType(), session.getOpenedAt(), closeInstant),
              repository.sumRefundsByTender(
                  session.getBusinessId(),
                  count.tenderType(),
                  session.getOpenedAt(),
                  closeInstant));
      long counted = count.countedMinor();
      long tenderOverShort = Math.subtractExact(counted, tenderExpected);
      RegisterSessionTender line =
          RegisterSessionTender.of(
              session.getId(),
              session.getBusinessId(),
              count.tenderType(),
              tenderExpected,
              counted,
              tenderOverShort,
              session.getCurrency());
      // Tenant column set explicitly, as the session is — the FORCE-RLS WITH CHECK requires it.
      line.setCompanyId(companyId);
      tenderRepository.save(line);
      lines.add(
          new RegisterSessionClosedSchema.TenderLine(
              count.tenderType(), tenderExpected, counted, tenderOverShort));
    }
    return lines;
  }

  /**
   * The live per-tender EXPECTED breakdown for an OPEN session (ADR 0038, phase 1) — a preview over
   * {@code [openedAt, now)} so the close screen can show, per tender, what the ledger says should
   * be there. Read-only, outlet-gated (review W1); NO {@link CashWindowLock} — this is an estimate,
   * not the authoritative close snapshot, so it must not serialize against live sales. Cash reuses
   * the drawer-accurate terms; card/QRIS/online use the per-tender net charged amount (sales −
   * refunds).
   */
  @Transactional(readOnly = true)
  public RegisterExpectedResponse expectedBreakdown(UUID sessionId) {
    RegisterSessionView session =
        repository
            .findViewById(sessionId)
            .orElseThrow(() -> new RegisterSessionNotFoundException(sessionId));
    outletAccessGuard.enforce(session.getBusinessId());
    if (!RegisterSession.STATUS_OPEN.equals(session.getStatus())) {
      throw new RegisterSessionNotOpenException(sessionId, session.getStatus());
    }

    UUID businessId = session.getBusinessId();
    Instant from = session.getOpenedAt();
    Instant asOf = Instant.now();

    // Cash INTO the drawer = float + cash-collected sales + cash gift-card sales − cash refunds
    // (mirrors the close formula, ADR 0036 §3). Overflow-safe (Math.*Exact) like the close.
    long cashExpected =
        Math.subtractExact(
            Math.addExact(
                session.getOpeningFloatMinor(),
                Math.addExact(
                    repository.sumCashSales(businessId, from, asOf),
                    repository.sumCashGiftCardSales(businessId, from, asOf))),
            repository.sumCashRefunds(businessId, from, asOf));

    List<TenderExpected> tenders =
        List.of(
            new TenderExpected(TenderType.CASH.name(), cashExpected),
            new TenderExpected(
                TenderType.CARD.name(), expectedForTender(businessId, TenderType.CARD, from, asOf)),
            new TenderExpected(
                TenderType.QRIS.name(), expectedForTender(businessId, TenderType.QRIS, from, asOf)),
            new TenderExpected(
                TenderType.ONLINE.name(),
                expectedForTender(businessId, TenderType.ONLINE, from, asOf)));

    return new RegisterExpectedResponse(
        sessionId,
        businessId,
        session.getCurrency() == null ? null : session.getCurrency().strip(),
        asOf,
        tenders);
  }

  /**
   * Net charged amount for a non-cash tender in the window: Σ sales − Σ refunds (overflow-safe).
   */
  private long expectedForTender(UUID businessId, TenderType tender, Instant from, Instant to) {
    return Math.subtractExact(
        repository.sumSalesByTender(businessId, tender.name(), from, to),
        repository.sumRefundsByTender(businessId, tender.name(), from, to));
  }

  /**
   * The POS daily transaction summary (Z-report) for a session — the aggregate sales figures + the
   * per-tender net + the cash reconciliation, all over ONE window so they agree. Unlike {@link
   * #expectedBreakdown} this works for a CLOSED session too (the final Z-report, and the till-menu
   * "today's summary" when the drawer is already closed): the window is {@code [openedAt,
   * closedAt)} for a CLOSED session, {@code [openedAt, now)} for an OPEN one. Read-only,
   * outlet-gated (review W1). Reporting only — finance's GL stays authoritative; the tax line is
   * illustrative-badged whenever any sale carried an illustrative rule.
   */
  @Transactional(readOnly = true)
  public RegisterSummaryResponse summarize(UUID sessionId) {
    RegisterSessionView session =
        repository
            .findViewById(sessionId)
            .orElseThrow(() -> new RegisterSessionNotFoundException(sessionId));
    outletAccessGuard.enforce(session.getBusinessId());

    UUID businessId = session.getBusinessId();
    Instant from = session.getOpenedAt();
    boolean open = RegisterSession.STATUS_OPEN.equals(session.getStatus());
    // A CLOSED session always has closed_at; fall back to now defensively so the window is valid.
    Instant asOf = open || session.getClosedAt() == null ? Instant.now() : session.getClosedAt();

    SaleSummaryView sales = repository.summarizeSales(businessId, from, asOf);

    // Cash terms computed once (reused for the tender line, the refunds total, and — for an OPEN
    // session — the live expected-cash figure below).
    long cashSales = repository.sumCashSales(businessId, from, asOf);
    long cashRefunds = repository.sumCashRefunds(businessId, from, asOf);

    // Per-tender GROSS sales (before refunds) — the conventional Z-report settlement breakdown: the
    // tender lines (+ the gift-card line below) foot to `total`, then the standalone `refunds` line
    // nets to `netSales`. Each per-tender GROSS already excludes any gift-card-settled portion
    // (cash uses cash_collected = amount − giftCard; non-cash uses amount − giftCard), so gift card
    // is its own 5th settlement line — Σ (tenders + giftCard) == Σ amount_minor == total.
    List<TenderSalesLine> tenders = new ArrayList<>(5);
    tenders.add(new TenderSalesLine(TenderType.CASH.name(), cashSales));
    tenders.add(
        new TenderSalesLine(
            TenderType.CARD.name(),
            repository.sumSalesByTender(businessId, TenderType.CARD.name(), from, asOf)));
    tenders.add(
        new TenderSalesLine(
            TenderType.QRIS.name(),
            repository.sumSalesByTender(businessId, TenderType.QRIS.name(), from, asOf)));
    tenders.add(
        new TenderSalesLine(
            TenderType.ONLINE.name(),
            repository.sumSalesByTender(businessId, TenderType.ONLINE.name(), from, asOf)));
    // Gift-card-redeemed-as-tender is a 5th settlement type (not a TenderType enum value) —
    // appended
    // only when non-zero so the breakdown foots without an always-zero line for the common
    // merchant.
    long giftCardSettled = repository.sumGiftCardRedeemed(businessId, from, asOf);
    if (giftCardSettled != 0) {
      tenders.add(new TenderSalesLine(RegisterSummaryResponse.TENDER_GIFT_CARD, giftCardSettled));
    }

    // Total refunds across all tenders (overflow-safe), so the report shows total / refunds / net.
    long refunds =
        Math.addExact(
            cashRefunds,
            Math.addExact(
                repository.sumRefundsByTender(businessId, TenderType.CARD.name(), from, asOf),
                Math.addExact(
                    repository.sumRefundsByTender(businessId, TenderType.QRIS.name(), from, asOf),
                    repository.sumRefundsByTender(
                        businessId, TenderType.ONLINE.name(), from, asOf))));
    long total = sales.getTotalMinor();
    long netSales = Math.subtractExact(total, refunds);

    // Cash reconciliation — live for OPEN (mirrors expectedBreakdown's cash formula: float + cash
    // sales + cash gift-card sales − cash refunds), snapshotted for CLOSED.
    long openingFloat = session.getOpeningFloatMinor();
    Long expectedCash;
    Long countedCash;
    Long overShort;
    if (open) {
      expectedCash =
          Math.subtractExact(
              Math.addExact(
                  openingFloat,
                  Math.addExact(
                      cashSales, repository.sumCashGiftCardSales(businessId, from, asOf))),
              cashRefunds);
      countedCash = null;
      overShort = null;
    } else {
      expectedCash = session.getExpectedCashMinor();
      countedCash = session.getCountedCashMinor();
      overShort = session.getOverShortMinor();
    }

    return new RegisterSummaryResponse(
        sessionId,
        businessId,
        session.getStatus(),
        session.getBusinessDate(),
        session.getCurrency() == null ? null : session.getCurrency().strip(),
        from,
        asOf,
        sales.getTxnCount(),
        sales.getGrossSalesMinor(),
        sales.getDiscountMinor(),
        sales.getLoyaltyRedeemedMinor(),
        sales.getServiceChargeMinor(),
        sales.getTaxMinor(),
        total,
        refunds,
        netSales,
        sales.getUsesIllustrativeRules(),
        tenders,
        openingFloat,
        expectedCash,
        countedCash,
        overShort);
  }

  /** Open-replay re-read used by the service's double-open race recovery. */
  @Transactional(readOnly = true)
  public Optional<RegisterSessionResponse> findByOpenKey(String idempotencyKey) {
    return repository
        .findViewByOpenIdempotencyKey(idempotencyKey)
        .map(RegisterSessionWriter::toResponse);
  }

  /** The outlet's current OPEN session, if any (outlet-assignment gated — review W1). */
  @Transactional(readOnly = true)
  public Optional<RegisterSessionResponse> findCurrent(UUID businessId) {
    outletAccessGuard.enforce(businessId);
    return repository.findOpenViewByBusinessId(businessId).map(RegisterSessionWriter::toResponse);
  }

  /** The outlet's session history (most recent first, capped at 50). */
  @Transactional(readOnly = true)
  public List<RegisterSessionResponse> findHistory(UUID businessId) {
    outletAccessGuard.enforce(businessId);
    return repository.findHistoryViewsByBusinessId(businessId).stream()
        .map(RegisterSessionWriter::toResponse)
        .toList();
  }

  /**
   * The outlet's CLOSED sessions with their day's net sales + transaction count, most recent first
   * — the manager/owner past-day history browse. Read-only, outlet-gated; each row's net equals
   * that session's Z-report net (the lateral aggregates mirror {@link #summarize}). Reporting only.
   */
  @Transactional(readOnly = true)
  public List<ClosedSessionSummaryResponse> findClosedHistory(UUID businessId) {
    outletAccessGuard.enforce(businessId);
    return repository
        .findClosedHistoryWithSalesByBusinessId(businessId, CLOSED_HISTORY_LIMIT)
        .stream()
        .map(RegisterSessionWriter::toClosedSummary)
        .toList();
  }

  /**
   * Maps the closed-history projection to its response row (CHAR(3) currency stripped). Lives in
   * the SERVICE layer — the dto boundary must not reach into projections (ArchUnit).
   */
  private static ClosedSessionSummaryResponse toClosedSummary(ClosedSessionSalesView v) {
    return new ClosedSessionSummaryResponse(
        v.getId(),
        v.getBusinessDate(),
        v.getOpenedAt(),
        v.getClosedAt(),
        v.getCurrency() == null ? null : v.getCurrency().strip(),
        v.getNetSalesMinor(),
        v.getTransactionCount());
  }

  /**
   * Maps the native read projection to the response shape (CHAR(3) currency stripped). Lives in the
   * SERVICE layer — the dto boundary must not reach into projections (ArchUnit).
   */
  private static RegisterSessionResponse toResponse(RegisterSessionView v) {
    return new RegisterSessionResponse(
        v.getId(),
        v.getBusinessId(),
        v.getStatus(),
        v.getBusinessDate(),
        v.getOpenedAt(),
        v.getOpeningFloatMinor(),
        v.getCurrency() == null ? null : v.getCurrency().strip(),
        v.getClosedAt(),
        v.getCashSalesMinor(),
        v.getCashRefundsMinor(),
        v.getExpectedCashMinor(),
        v.getCountedCashMinor(),
        v.getOverShortMinor());
  }
}
