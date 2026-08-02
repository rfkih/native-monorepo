package id.co.nativeapp.restaurant.bill.dto;

import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/bills/{id}/pay} — finalise an OPEN bill, or pay a partial
 * subset of its lines as one check (Increment 3 split-by-item).
 *
 * <p><strong>Full-bill pay (existing behaviour):</strong> omit {@code lineIds} (null or empty).
 * Every still-unpaid line on the bill is included in this check.
 *
 * <p><strong>Split check:</strong> supply {@code lineIds} to restrict this check to a subset of the
 * bill's still-unpaid lines. The bill stays OPEN until all lines are paid, then transitions to PAID
 * automatically.
 *
 * <p>The {@code idempotencyKey} is the caller-supplied idempotency key for the check's sale. When
 * null, one is derived deterministically from the bill id and the sorted set of target line ids so
 * retries are safe. For the full-bill path the key defaults to {@code billId + ":bill-sale"} (same
 * as before).
 *
 * @param payment optional payment instruction (cash / digital / ONLINE, ADR 0036 Phase B2)
 * @param discountMinor optional fixed discount in minor units (&ge; 0); applied to this check's
 *     subtotal only
 * @param lineIds optional set of line ids to include in this check; null/empty = all unpaid lines
 * @param idempotencyKey optional caller-supplied idempotency key for the check sale
 * @param channelCode Phase B2 (ADR 0036): the company-managed {@code sales_channel.code} this check
 *     rings through — REQUIRED when {@code payment.tenderType() == ONLINE} (a platform-collected
 *     order, e.g. GoFood/GrabFood), validated to exist and be active for the tenant; ignored/must
 *     be omitted for every other tender. See {@code BillWriter.validateOnlineTender}.
 */
public record PayBillRequest(
    @Valid PaymentRequest payment,
    @Min(0) Long discountMinor,
    List<UUID> lineIds,
    String idempotencyKey,
    String channelCode) {

  /** Convenience: pay all remaining lines with no tender and no discount. */
  public PayBillRequest() {
    this(null, null, null, null, null);
  }

  /** Convenience: pay all remaining lines with no tender. */
  public PayBillRequest(@Valid PaymentRequest payment, @Min(0) Long discountMinor) {
    this(payment, discountMinor, null, null, null);
  }
}
