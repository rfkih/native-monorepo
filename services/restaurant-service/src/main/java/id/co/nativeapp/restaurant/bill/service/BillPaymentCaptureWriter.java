package id.co.nativeapp.restaurant.bill.service;

import id.co.nativeapp.restaurant.bill.domain.Bill;
import id.co.nativeapp.restaurant.bill.projection.BillLineView;
import id.co.nativeapp.restaurant.bill.repository.BillLineRepository;
import id.co.nativeapp.restaurant.bill.repository.BillRepository;
import id.co.nativeapp.restaurant.payment.domain.Payment;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.restaurant.register.service.CashWindowLock;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that captures a PENDING BILL gateway payment
 * (V38, dynamic QRIS/CARD, full-bill only): records the check's Sale, emits {@code SaleRecorded},
 * transitions the payment to {@link Payment.Status#CAPTURED}, and moves the bill to PAID once every
 * line on it is paid — atomically (mirrors {@code PaymentCaptureWriter} for the order path exactly,
 * but stays a SEPARATE class so the order path is never touched by this change).
 *
 * <p>A distinct bean so {@link #capture} is invoked through the Spring proxy: the
 * {@code @Transactional} advice and the {@link RlsAutoApplyAspect} both engage (the {@code
 * SaleWriter}/ {@code OrderWriter}/{@code BillWriter} pattern).
 *
 * <p><strong>Idempotency.</strong> If the payment is already {@link Payment.Status#CAPTURED}
 * (re-delivery), this returns immediately with no side effects — no second sale, no second stock
 * deduction. The check's own sale idempotency key is derived ON-THE-FLY here, from {@code
 * payment.getId() + ":bill-capture-sale"} — mirroring EXACTLY how the order path's {@code
 * PaymentCaptureWriter#capture} derives {@code payment.getId() + ":capture-sale"} (HIGH fix, code
 * review: an earlier cut stamped a BILL-WIDE shared key at mint time — {@code "<bill_id>:bill-
 * sale"}, the SAME key the cash path uses — so a bill's SECOND check in its lifetime collided on
 * {@code uq_sale_company_idempotency}; per-payment derivation can never collide across a bill's
 * multiple checks). This key is ALSO the DB-level backstop against a genuine concurrent race (two
 * capture calls both observing PENDING): the loser's {@code sale (company_id, idempotency_key)}
 * INSERT hits the UNIQUE constraint and throws {@link
 * org.springframework.dao.DataIntegrityViolationException}, which {@code
 * PaymentCaptureService#capture} (the manual-capture dispatch) already catches and recovers from —
 * exactly the order-path contract. The ASYNC {@code PaymentChargeSucceededWriter} webhook path ALSO
 * catches this collision now (HIGH fix) and parks instead of poison-looping the Kafka redelivery.
 *
 * <p><strong>Recompute-and-assert (park, don't capture, on mismatch).</strong> Unlike an ORDER
 * (whose lines/total are immutable once checkout completes), a BILL is a long-lived, MUTABLE tab —
 * a reserved line could in principle be removed, or a promotions rule edited, between {@code
 * BillWriter.initiatePendingPayment}'s mint and this capture. So {@link #capture} recomputes the
 * check breakdown from the RESERVED lines ({@code bill_line.pending_payment_id = paymentId}) and
 * the payment's own stored {@code discountMinor}, priced at the ORIGINAL mint instant ({@link
 * Payment#getOccurredAt()} — not {@code Instant.now()}, so an effective-dated tax/service-charge
 * rule change since mint never causes a false mismatch), and ASSERTS it reconciles to {@link
 * Payment#getAmount()}. A mismatch throws {@link IllegalStateException} — caught by {@code
 * PaymentChargeSucceededWriter}'s existing park-don't-drop handling (real money may already be
 * moving at the gateway; a human must resolve) — rather than silently capturing a different amount.
 */
@Component
public class BillPaymentCaptureWriter {

  private final PaymentRepository paymentRepository;
  private final BillRepository billRepository;
  private final BillLineRepository lineRepository;
  private final BillWriter billWriter;
  private final CashWindowLock cashWindowLock;

  public BillPaymentCaptureWriter(
      PaymentRepository paymentRepository,
      BillRepository billRepository,
      BillLineRepository lineRepository,
      BillWriter billWriter,
      CashWindowLock cashWindowLock) {
    this.paymentRepository = paymentRepository;
    this.billRepository = billRepository;
    this.lineRepository = lineRepository;
    this.billWriter = billWriter;
    this.cashWindowLock = cashWindowLock;
  }

  /**
   * Captures a {@link Payment.Status#PENDING} bill gateway payment: records the check's Sale, emits
   * {@code SaleRecorded}, transitions the payment to {@link Payment.Status#CAPTURED}, clears the
   * line reservation, and moves the bill to PAID once every line on it is paid. Runs in {@code
   * REQUIRES_NEW} so it has its own transaction boundary (same pattern as {@code
   * PaymentCaptureWriter#capture}).
   *
   * @param paymentId the payment to capture (must be a PENDING bill payment visible to the current
   *     tenant)
   * @return the captured payment response
   * @throws IllegalArgumentException if the payment is not found, is not a bill payment, is not a
   *     digital tender, or its bill is not found
   * @throws IllegalStateException if the payment is not PENDING, has no reserved lines to capture,
   *     or its recomputed check breakdown does not reconcile to {@link Payment#getAmount()} (parked
   *     by the caller instead of capturing)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PaymentResponse capture(UUID paymentId) {
    TenantContext.require();

    // Load the full aggregate (write path).
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

    if (!payment.isForBill()) {
      throw new IllegalArgumentException(
          "Payment "
              + paymentId
              + " is not a bill payment; order payments capture via PaymentCaptureWriter");
    }
    if (!payment.getTenderType().isDigital()) {
      throw new IllegalArgumentException(
          "Only digital payments can be captured via this endpoint; tender="
              + payment.getTenderType());
    }

    if (payment.getStatus() == Payment.Status.CAPTURED) {
      // Idempotent re-delivery: return the existing state without side effects.
      return PaymentResponse.from(payment);
    }
    if (payment.getStatus() != Payment.Status.PENDING) {
      throw new IllegalStateException(
          "only a PENDING bill payment can be captured; payment "
              + paymentId
              + " is "
              + payment.getStatus());
    }

    // FOR UPDATE (audit C1/H1): capture marks bill_line rows paid, so it must serialize on the
    // bill row like every other bill write path (canonical lock order bill → bill_line → payment —
    // the payment row is only locked at the very end, by capture() + saveAndFlush below). See
    // BillRepository#findWithLockById.
    Bill bill =
        billRepository
            .findWithLockById(payment.getBillId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Bill not found for payment: "
                            + paymentId
                            + " billId="
                            + payment.getBillId()));

    List<BillLineView> reservedLines = lineRepository.findViewsByPendingPaymentId(paymentId);
    if (reservedLines.isEmpty()) {
      throw new IllegalStateException(
          "Payment "
              + paymentId
              + " has no reserved bill_line rows to capture (bill "
              + bill.getId()
              + ")");
    }

    String currency = bill.getCurrency().strip();

    // CashWindowLock (verified HIGH race fix) — SHARED, FIRST lock-acquiring statement in THIS
    // method, strictly BEFORE capturedAt is captured below (mirrors PaymentCaptureWriter#capture
    // for the order path; BillWriter.recordCheck deliberately does NOT re-acquire it — see its
    // javadoc). Everything above this point is a plain read or a no-write idempotent/failure
    // short-circuit.
    cashWindowLock.acquireForCommit(bill.getBusinessId());
    Instant capturedAt = Instant.now();

    // Recompute-and-assert guard (see class javadoc): priced at the ORIGINAL mint instant, not
    // Instant.now(), so an effective-dated tax/service-charge rule change since mint never causes
    // a false mismatch.
    BillWriter.CheckPricing guard =
        billWriter.priceCheck(
            reservedLines, currency, payment.getDiscountMinor(), payment.getOccurredAt());
    if (!guard.breakdown().grandTotal().equals(payment.getAmount())) {
      throw new IllegalStateException(
          "Bill capture breakdown mismatch for payment "
              + paymentId
              + ": authorized "
              + payment.getAmount().amountMinor()
              + " "
              + currency
              + ", recomputed "
              + guard.breakdown().grandTotal().amountMinor()
              + " "
              + currency
              + " — parking instead of capturing (real money may already be moving at the"
              + " gateway)");
    }

    // HIGH fix (code review): derive the sale idempotency key from THIS payment's own id, at
    // capture time — never a value shared across a bill's multiple checks. See class javadoc.
    String saleIdempotencyKey = payment.getId() + ":bill-capture-sale";

    UUID saleId =
        billWriter.recordCheck(
            bill,
            reservedLines,
            payment.getDiscountMinor(),
            saleIdempotencyKey,
            payment.getTenderType().name(),
            null,
            payment.getOccurredAt(),
            capturedAt,
            payment.getId(), // capturingPaymentId (C1 fix) — the guarded capture-path mark-paid
            guard); // precomputedPricing (S3) — reuse the guard's already-computed breakdown

    // Capture the payment aggregate: PENDING → CAPTURED, sets sale_id + captured_at.
    payment.capture(saleId, capturedAt);
    paymentRepository.saveAndFlush(payment);

    return PaymentResponse.from(payment);
  }
}
