package id.co.nativeapp.payment.charge.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.payment.charge.domain.ChargeConflictException;
import id.co.nativeapp.payment.charge.domain.ChargeExpiryReason;
import id.co.nativeapp.payment.charge.domain.ChargeNotFoundException;
import id.co.nativeapp.payment.charge.domain.ChargeStatus;
import id.co.nativeapp.payment.charge.domain.ChargeValidationException;
import id.co.nativeapp.payment.charge.domain.PaymentCharge;
import id.co.nativeapp.payment.charge.messaging.PaymentChargeExpiredSchema;
import id.co.nativeapp.payment.charge.messaging.PaymentChargeSucceededSchema;
import id.co.nativeapp.payment.charge.repository.PaymentChargeRepository;
import id.co.nativeapp.payment.settings.domain.PaymentSettings;
import id.co.nativeapp.payment.settings.domain.QrisMode;
import id.co.nativeapp.payment.settings.repository.PaymentSettingsRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for the charge lifecycle (ADR 0045). The
 * two-transaction shape is deliberate: {@link #createInitiated} COMMITS the INITIATED anchor row
 * before {@code ChargeService} makes the outbound PSP call (a webhook can never race an uncommitted
 * charge), and {@link #markQrIssued}/{@link #markFailed} record the outcome in a second
 * transaction. The PSP call itself never runs inside a DB transaction.
 *
 * <p><strong>Idempotency (the PlatformSettlementWriter discipline).</strong> Replay-by-key first:
 * the same {@code Idempotency-Key} with the same payload returns the existing charge; a different
 * payload is a 409. Independently, at most ONE live charge exists per payment (V3 partial unique) —
 * a create against a live charge replays it, and the unique index closes the probe-then-insert race
 * (the violation re-probes instead of failing).
 *
 * <p><strong>Settlement emits exactly once.</strong> {@link #applySettlement} flips a LIVE charge
 * to SUCCEEDED and writes the {@code PaymentChargeSucceeded} outbox row in the SAME transaction
 * (rule 3); the {@code saveAndFlush} forces the optimistic-version check BEFORE the outbox insert,
 * so a webhook/sync race commits one transition and one event — the loser's transaction rolls back
 * whole.
 */
@Component
public class ChargeWriter {

  private static final Set<ChargeStatus> LIVE =
      EnumSet.of(ChargeStatus.INITIATED, ChargeStatus.QR_ISSUED);

  private static final Set<String> VERTICALS = Set.of("restaurant", "carwash", "barbershop");

  /** Grace past the QR expiry before the lazy sweep flips QR_ISSUED → EXPIRED. */
  private static final long EXPIRY_GRACE_SECONDS = 60;

  private final PaymentChargeRepository charges;
  private final PaymentSettingsRepository settings;
  private final OutboxWriter outboxWriter;

  public ChargeWriter(
      PaymentChargeRepository charges,
      PaymentSettingsRepository settings,
      OutboxWriter outboxWriter) {
    this.charges = charges;
    this.settings = settings;
    this.outboxWriter = outboxWriter;
  }

  /** What {@link #createInitiated} hands back to the orchestrating service. */
  public record CreateOutcome(
      PaymentCharge charge, boolean fresh, QrisGatewayPort.GatewayCredentials credentials) {}

  /** A charge + the credentials a remote (sync/cancel) operation needs. */
  public record RemoteOp(PaymentCharge charge, QrisGatewayPort.GatewayCredentials credentials) {}

  /**
   * Creates (or replays) the INITIATED anchor row.
   *
   * @throws ChargeValidationException non-IDR currency / unknown vertical (→ 422)
   * @throws ChargeConflictException same key + different payload; effective mode ≠ GATEWAY; no
   *     credentials configured (→ 409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CreateOutcome createInitiated(
      String vertical,
      UUID paymentId,
      UUID referenceId,
      UUID businessId,
      UUID divisionId,
      long amountMinor,
      String currency,
      String idempotencyKey) {
    TenantContext.require();
    if (!VERTICALS.contains(vertical)) {
      throw new ChargeValidationException(
          "Unknown vertical: must be restaurant, carwash or barbershop");
    }
    if (!"IDR".equals(currency)) {
      throw new ChargeValidationException("QRIS charges are IDR-only (ADR 0045)");
    }

    // Replay-by-key first: same key + same payload → the existing charge; different payload → 409.
    // divisionId is deliberately excluded from the payload match — it is advisory only.
    Optional<PaymentCharge> byKey = charges.findByIdempotencyKey(idempotencyKey);
    if (byKey.isPresent()) {
      PaymentCharge existing = byKey.get();
      requirePayloadMatch(existing, paymentId, amountMinor, currency);
      return new CreateOutcome(existing, false, null);
    }

    // One live charge per payment: a create against it replays it (the till re-shows its QR).
    Optional<PaymentCharge> live = charges.findFirstByPaymentIdAndStatusIn(paymentId, LIVE);
    if (live.isPresent()) {
      return new CreateOutcome(live.get(), false, null);
    }

    QrisGatewayPort.GatewayCredentials credentials = requireGatewayReady(businessId, divisionId);

    PaymentCharge charge =
        new PaymentCharge(
            vertical,
            paymentId,
            referenceId,
            businessId,
            amountMinor,
            currency,
            id.co.nativeapp.payment.settings.domain.PspProvider.MIDTRANS,
            idempotencyKey);
    charge.setCompanyId(TenantContext.require().companyId());
    try {
      charges.saveAndFlush(charge);
    } catch (DataIntegrityViolationException e) {
      // The probe-then-insert race: a concurrent create won one of the unique indexes — replay
      // whichever row won instead of failing the till.
      Optional<PaymentCharge> winner =
          charges
              .findByIdempotencyKey(idempotencyKey)
              .or(() -> charges.findFirstByPaymentIdAndStatusIn(paymentId, LIVE));
      if (winner.isPresent()) {
        requirePayloadMatch(winner.get(), paymentId, amountMinor, currency);
        return new CreateOutcome(winner.get(), false, null);
      }
      throw e;
    }
    return new CreateOutcome(charge, true, credentials);
  }

  /** INITIATED → QR_ISSUED with the PSP's QR payload. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markQrIssued(UUID chargeId, QrisGatewayPort.QrCreated qr) {
    PaymentCharge charge = require(chargeId);
    charge.markQrIssued(qr.qrString(), qr.qrUrl(), qr.providerTxnId(), qr.expiresAt());
    charges.saveAndFlush(charge);
  }

  /** live → FAILED (no-op on a terminal charge — the PSP outcome may race a cancel). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markFailed(UUID chargeId) {
    terminateIfLive(chargeId, PaymentCharge::markFailed, ChargeExpiryReason.FAILED);
  }

  /** live → CANCELED (no-op on a terminal charge). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markCanceled(UUID chargeId) {
    terminateIfLive(chargeId, PaymentCharge::markCanceled, ChargeExpiryReason.CANCELED);
  }

  /** live → EXPIRED (no-op on a terminal charge). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markExpired(UUID chargeId) {
    terminateIfLive(chargeId, PaymentCharge::markExpired, ChargeExpiryReason.EXPIRED);
  }

  /**
   * The lazy expiry sweep (the fleet's no-@Scheduled idiom): flips QR_ISSUED → EXPIRED once the QR
   * is past its expiry + grace, and emits {@code PaymentChargeExpired} so the vertical releases the
   * PENDING tender it was holding. Called from the till's poll read path.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void expireIfPast(UUID chargeId, Instant now) {
    PaymentCharge charge = require(chargeId);
    if (charge.getStatus() == ChargeStatus.QR_ISSUED
        && charge.getExpiresAt() != null
        && now.isAfter(charge.getExpiresAt().plusSeconds(EXPIRY_GRACE_SECONDS))) {
      charge.markExpired();
      // Flush BEFORE the outbox emit: a concurrent settle that already flipped this row to SUCCEEDED
      // makes this flush throw on the version check and rolls the whole tx back — no EXPIRED event.
      charges.saveAndFlush(charge);
      emitChargeExpired(charge, ChargeExpiryReason.EXPIRED, now);
    }
  }

  /**
   * live → SUCCEEDED + the {@code PaymentChargeSucceeded} outbox row, in ONE transaction.
   *
   * @return {@code true} if this call performed the transition; {@code false} if the charge was
   *     already terminal (duplicate webhook / sync race — no second event)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean applySettlement(UUID chargeId, String providerTxnId, Instant succeededAt) {
    PaymentCharge charge = require(chargeId);
    if (!charge.isLive()) {
      return false;
    }
    charge.markSucceeded(providerTxnId, succeededAt);
    // Force the optimistic-version check BEFORE the outbox insert: a concurrent settlement makes
    // this flush throw and the whole transaction (no event) roll back.
    charges.saveAndFlush(charge);

    byte[] payload =
        AvroSerde.serialize(
            PaymentChargeSucceededSchema.toRecord(
                charge.getId(),
                charge.getCompanyId(),
                charge.getVertical(),
                charge.getPaymentId(),
                charge.getReferenceId(),
                charge.getBusinessId(),
                charge.getAmountMinor(),
                charge.getCurrency(),
                charge.getProvider().name(),
                charge.getProviderTxnId(),
                succeededAt));
    outboxWriter.write(
        PaymentChargeSucceededSchema.AGGREGATE_TYPE,
        charge.getId().toString(),
        PaymentChargeSucceededSchema.EVENT_TYPE,
        payload,
        null,
        UUID.fromString(charge.getCompanyId()),
        succeededAt);
    return true;
  }

  /**
   * The company's decrypted server key, for webhook signature verification (rule 6: call-scoped
   * only). Empty when no GATEWAY credentials exist — under a PROVISIONALLY bound (possibly forged)
   * tenant, RLS makes the row invisible, so a forged {@code companyId} lands here and is rejected
   * uniformly.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<String> companyServerKey() {
    return settings
        .findByOrgUnitIdIsNull()
        .filter(PaymentSettings::hasServerKey)
        .map(PaymentSettings::getServerKey);
  }

  /** The webhook's charge lookup by Midtrans order_id (RLS-scoped like everything else). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<PaymentCharge> chargeByProviderOrderId(String providerOrderId) {
    return charges.findByProviderOrderId(providerOrderId);
  }

  /**
   * A FRESH read of a charge — the post-settle verification (code review C1): when {@link
   * #applySettlement} declined because the charge was no longer live, the caller must distinguish
   * "a concurrent settle already SUCCEEDED it" (correct no-op) from "it went
   * CANCELED/EXPIRED/FAILED in the race window" (real money with no capture — must park).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PaymentCharge chargeById(UUID chargeId) {
    return require(chargeId);
  }

  /** Loads a charge + the company's credentials for a remote (sync/cancel) operation. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RemoteOp loadForRemoteOp(UUID chargeId) {
    PaymentCharge charge = require(chargeId);
    if (!charge.isLive()) {
      return new RemoteOp(charge, null);
    }
    return new RemoteOp(charge, requireCompanyCredentials());
  }

  /**
   * live → a terminal non-SUCCEEDED state (FAILED/CANCELED/EXPIRED), no-op on an already-terminal
   * charge. When the prior state was QR_ISSUED — the only state in which a customer-facing QR
   * existed and a vertical PENDING tender is genuinely waiting on the async settlement — it ALSO
   * emits {@code PaymentChargeExpired} in this same transaction so the vertical releases that
   * tender. An INITIATED-state termination (e.g. the gateway never issued a QR) emits nothing: the
   * originating {@code create()} call fails synchronously to the caller, which handles it in-band.
   */
  private void terminateIfLive(
      UUID chargeId,
      java.util.function.Consumer<PaymentCharge> transition,
      ChargeExpiryReason reason) {
    PaymentCharge charge = require(chargeId);
    if (!charge.isLive()) {
      return;
    }
    boolean wasQrIssued = charge.getStatus() == ChargeStatus.QR_ISSUED;
    transition.accept(charge);
    // Force the optimistic-version check BEFORE the outbox insert (the applySettlement discipline):
    // a concurrent transition makes this flush throw and the whole transaction — no event — rolls
    // back.
    charges.saveAndFlush(charge);
    if (wasQrIssued) {
      emitChargeExpired(charge, reason, Instant.now());
    }
  }

  /**
   * Writes the {@code PaymentChargeExpired} outbox row (rule 3) for a charge that terminated without
   * settling, in the CALLER's transaction. The counterpart of {@link #applySettlement}'s emit on the
   * un-happy path — carries no money movement, only the release signal + audit fields.
   */
  private void emitChargeExpired(PaymentCharge charge, ChargeExpiryReason reason, Instant occurredAt) {
    byte[] payload =
        AvroSerde.serialize(
            PaymentChargeExpiredSchema.toRecord(
                charge.getId(),
                charge.getCompanyId(),
                charge.getVertical(),
                charge.getPaymentId(),
                charge.getReferenceId(),
                charge.getBusinessId(),
                charge.getAmountMinor(),
                charge.getCurrency(),
                reason.name(),
                occurredAt));
    outboxWriter.write(
        PaymentChargeExpiredSchema.AGGREGATE_TYPE,
        charge.getId().toString(),
        PaymentChargeExpiredSchema.EVENT_TYPE,
        payload,
        null,
        UUID.fromString(charge.getCompanyId()),
        occurredAt);
  }

  private PaymentCharge require(UUID chargeId) {
    Objects.requireNonNull(chargeId, "chargeId");
    return charges.findById(chargeId).orElseThrow(ChargeNotFoundException::new);
  }

  /**
   * The effective mode for {@code businessId ?? divisionId ?? company} must be GATEWAY and the
   * COMPANY row must carry usable credentials (they are company-level, ADR 0045 amendment).
   */
  private QrisGatewayPort.GatewayCredentials requireGatewayReady(UUID businessId, UUID divisionId) {
    Optional<PaymentSettings> companyRow = settings.findByOrgUnitIdIsNull();
    Optional<PaymentSettings> outletRow =
        businessId == null ? Optional.empty() : settings.findByOrgUnitId(businessId);
    Optional<PaymentSettings> divisionRow =
        divisionId == null ? Optional.empty() : settings.findByOrgUnitId(divisionId);
    QrisMode mode =
        outletRow
            .map(PaymentSettings::getMode)
            .or(() -> divisionRow.map(PaymentSettings::getMode))
            .or(() -> companyRow.map(PaymentSettings::getMode))
            .orElse(QrisMode.MANUAL);
    if (mode != QrisMode.GATEWAY) {
      throw new ChargeConflictException(
          "The outlet's effective QRIS mode is " + mode + ", not GATEWAY.");
    }
    return companyRow
        .filter(PaymentSettings::hasServerKey)
        .map(this::toCredentials)
        .orElseThrow(
            () ->
                new ChargeConflictException(
                    "GATEWAY mode is set but no Midtrans credentials are configured."));
  }

  private QrisGatewayPort.GatewayCredentials requireCompanyCredentials() {
    return settings
        .findByOrgUnitIdIsNull()
        .filter(PaymentSettings::hasServerKey)
        .map(this::toCredentials)
        .orElseThrow(
            () ->
                new ChargeConflictException(
                    "No Midtrans credentials are configured for this company."));
  }

  private QrisGatewayPort.GatewayCredentials toCredentials(PaymentSettings row) {
    if (row.getProviderEnvironment() == null) {
      throw new ChargeConflictException("The gateway environment (SANDBOX/PRODUCTION) is not set.");
    }
    return new QrisGatewayPort.GatewayCredentials(row.getServerKey(), row.getProviderEnvironment());
  }

  private static void requirePayloadMatch(
      PaymentCharge existing, UUID paymentId, long amountMinor, String currency) {
    boolean matches =
        existing.getPaymentId().equals(paymentId)
            && existing.getAmountMinor() == amountMinor
            && existing.getCurrency().equals(currency);
    if (!matches) {
      throw new ChargeConflictException(
          "This Idempotency-Key was already used with a different payload.");
    }
  }
}
