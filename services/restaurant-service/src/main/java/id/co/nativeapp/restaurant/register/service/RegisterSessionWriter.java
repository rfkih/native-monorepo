package id.co.nativeapp.restaurant.register.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.register.domain.RegisterSession;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionIdempotencyKeyConflictException;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionNotFoundException;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionNotOpenException;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionResult;
import id.co.nativeapp.restaurant.register.dto.RegisterSessionResponse;
import id.co.nativeapp.restaurant.register.messaging.RegisterSessionClosedSchema;
import id.co.nativeapp.restaurant.register.projection.RegisterSessionView;
import id.co.nativeapp.restaurant.register.repository.RegisterSessionRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
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
 */
@Component
public class RegisterSessionWriter {

  /**
   * v1 default zone for deriving {@code business_date} when the request omits it — Indonesian SMEs
   * (documented simplification, ADR 0036; a per-outlet timezone is the additive follow-up).
   */
  private static final ZoneId DEFAULT_BUSINESS_ZONE = ZoneId.of("Asia/Jakarta");

  private final RegisterSessionRepository repository;
  private final OutboxWriter outboxWriter;
  private final OutletAccessGuard outletAccessGuard;

  public RegisterSessionWriter(
      RegisterSessionRepository repository,
      OutboxWriter outboxWriter,
      OutletAccessGuard outletAccessGuard) {
    this.repository = repository;
    this.outboxWriter = outboxWriter;
    this.outletAccessGuard = outletAccessGuard;
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
      // Review W3: same key must mean the same logical close (same session, same count).
      Long replayCounted = existing.getCountedCashMinor();
      if (!existing.getId().equals(sessionId)
          || replayCounted == null
          || !replayCounted.equals(request.countedCashMinor())) {
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
            session.getCurrency());
    outboxWriter.write(
        RegisterSessionClosedSchema.AGGREGATE_TYPE,
        session.getId().toString(),
        RegisterSessionClosedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        UUID.fromString(companyId),
        closeInstant);

    return RegisterSessionResponse.from(session);
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
