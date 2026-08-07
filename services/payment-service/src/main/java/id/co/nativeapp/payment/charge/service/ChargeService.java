package id.co.nativeapp.payment.charge.service;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.payment.charge.domain.ChargeStatus;
import id.co.nativeapp.payment.charge.domain.GatewayUnavailableException;
import id.co.nativeapp.payment.charge.domain.PaymentCharge;
import id.co.nativeapp.payment.charge.dto.ChargeResponse;
import id.co.nativeapp.payment.charge.dto.CreateChargeRequest;
import id.co.nativeapp.payment.charge.projection.ChargeView;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the charge lifecycle across the transaction boundary and the PSP edge (ADR 0045):
 * every DB unit of work is a {@link ChargeWriter} REQUIRES_NEW transaction; every {@link
 * QrisGatewayPort} call runs BETWEEN them, never inside one. The merchant's decrypted server key
 * exists only in call-scope locals on this path (rule 6).
 */
@Service
public class ChargeService {

  private final ChargeWriter writer;
  private final ChargeReader reader;
  private final QrisGatewayPort gateway;
  private final ErrorInboxWriter errorInbox;

  public ChargeService(
      ChargeWriter writer,
      ChargeReader reader,
      QrisGatewayPort gateway,
      ErrorInboxWriter errorInbox) {
    this.writer = writer;
    this.reader = reader;
    this.gateway = gateway;
    this.errorInbox = errorInbox;
  }

  /** A create result: the response plus whether this call minted a NEW charge (→ 201 vs 200). */
  public record CreateResult(ChargeResponse response, boolean fresh) {}

  /**
   * Creates a dynamic-QRIS charge for a vertical's PENDING payment — or replays the existing one
   * (same Idempotency-Key, or the payment's live charge). Tx1 commits the INITIATED anchor; the
   * Midtrans call runs outside any transaction; tx2 records QR_ISSUED or FAILED.
   */
  public CreateResult create(CreateChargeRequest request, String idempotencyKey) {
    ChargeWriter.CreateOutcome outcome =
        writer.createInitiated(
            request.vertical(),
            request.paymentId(),
            request.referenceId(),
            request.businessId(),
            request.amountMinor(),
            request.currency(),
            idempotencyKey);
    if (!outcome.fresh()) {
      return new CreateResult(toResponse(reader.view(outcome.charge().getId())), false);
    }

    UUID chargeId = outcome.charge().getId();
    UUID companyId = UUID.fromString(TenantContext.require().companyId());
    QrisGatewayPort.QrCreated qr;
    try {
      qr =
          gateway.createQr(
              outcome.credentials(),
              companyId,
              outcome.charge().getProviderOrderId(),
              outcome.charge().getAmountMinor());
    } catch (GatewayUnavailableException e) {
      // The PSP never yielded a QR — the charge is dead; the till falls back to another tender.
      writer.markFailed(chargeId);
      throw e;
    }
    writer.markQrIssued(chargeId, qr);
    return new CreateResult(toResponse(reader.view(chargeId)), true);
  }

  /** The till's poll — runs the lazy past-expiry sweep first (the no-@Scheduled idiom). */
  public ChargeResponse get(UUID chargeId) {
    writer.expireIfPast(chargeId, Instant.now());
    return toResponse(reader.view(chargeId));
  }

  /**
   * The reconcile fallback ("customer says they paid, no webhook arrived"): probes Midtrans
   * server-side and applies the SAME idempotent transition the webhook applies — including the
   * outbox emit on settlement.
   */
  public ChargeResponse sync(UUID chargeId) {
    ChargeWriter.RemoteOp op = writer.loadForRemoteOp(chargeId);
    if (!op.charge().isLive()) {
      return toResponse(reader.view(chargeId));
    }
    QrisGatewayPort.RemoteCharge remote =
        gateway.status(op.credentials(), op.charge().getProviderOrderId());
    switch (remote.status()) {
      case SETTLED -> settle(chargeId, remote.providerTxnId());
      case EXPIRED -> writer.markExpired(chargeId);
      case CANCELED -> writer.markCanceled(chargeId);
      case FAILED -> writer.markFailed(chargeId);
      case NOT_FOUND -> {
        // The create call never reached Midtrans (a tx1-only orphan): dead, retry-able.
        if (op.charge().getStatus() == ChargeStatus.INITIATED) {
          writer.markFailed(chargeId);
        }
      }
      case PENDING -> {
        // Still waiting — nothing to record.
      }
      default -> throw new IllegalStateException("Unhandled remote status: " + remote.status());
    }
    return toResponse(reader.view(chargeId));
  }

  /**
   * Cashier cancel. If Midtrans reports the money already moved, the settlement path runs instead —
   * real money is never swallowed by a local cancel (the late payment then captures normally).
   */
  public ChargeResponse cancel(UUID chargeId) {
    ChargeWriter.RemoteOp op = writer.loadForRemoteOp(chargeId);
    if (!op.charge().isLive()) {
      return toResponse(reader.view(chargeId));
    }
    QrisGatewayPort.CancelOutcome outcome =
        gateway.cancel(op.credentials(), op.charge().getProviderOrderId());
    switch (outcome) {
      case ALREADY_SETTLED -> settle(chargeId, null);
      case CANCELED, NOT_FOUND -> writer.markCanceled(chargeId);
      default -> throw new IllegalStateException("Unhandled cancel outcome: " + outcome);
    }
    return toResponse(reader.view(chargeId));
  }

  private void settle(UUID chargeId, String providerTxnId) {
    boolean applied = false;
    try {
      applied = writer.applySettlement(chargeId, providerTxnId, Instant.now());
    } catch (OptimisticLockingFailureException concurrentSettle) {
      // A concurrent webhook/sync transition won the version check — verify the winner below.
    }
    if (!applied) {
      // Code review C1 (the webhook path's twin): a declined applySettlement is a correct no-op
      // only when a concurrent settle already SUCCEEDED the charge. If the lazy expiry sweep (or
      // a cancel) won the race window instead, Midtrans holds real money with no local capture —
      // park for a human, never drop.
      PaymentCharge fresh = writer.chargeById(chargeId);
      if (fresh.getStatus() != ChargeStatus.SUCCEEDED) {
        String companyId = TenantContext.require().companyId();
        errorInbox.record(
            new IllegalStateException(
                "Midtrans reports charge "
                    + fresh.getId()
                    + " ("
                    + fresh.getAmountMinor()
                    + " "
                    + fresh.getCurrency()
                    + ") settled, but it went "
                    + fresh.getStatus()
                    + " locally during the sync/cancel — refund the customer via the Midtrans"
                    + " dashboard"),
            "payment.charge-sync.late-settlement",
            companyId,
            null);
      }
    }
  }

  private static ChargeResponse toResponse(ChargeView view) {
    return new ChargeResponse(
        view.getId(),
        view.getStatus(),
        view.getVertical(),
        view.getPaymentId(),
        view.getQrString(),
        view.getQrUrl(),
        view.getExpiresAt(),
        view.getAmountMinor(),
        view.getCurrency() == null ? null : view.getCurrency().strip());
  }
}
