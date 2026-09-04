package id.co.nativeapp.restaurant.bill.service;

import id.co.nativeapp.restaurant.bill.dto.AppendLinesRequest;
import id.co.nativeapp.restaurant.bill.dto.BillResponse;
import id.co.nativeapp.restaurant.bill.dto.BillSummaryResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.dto.PayBillRequest;
import id.co.nativeapp.restaurant.bill.projection.BillSummaryView;
import id.co.nativeapp.restaurant.bill.repository.BillRepository;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates bill lifecycle: open, append, list, get, pay, cancel.
 *
 * <p>Not itself {@code @Transactional} — transactional units live in {@link BillWriter} (writes) so
 * the proxy and the RLS aspect engage. Follows the same {@code OrderService} / {@code SaleService}
 * pattern exactly.
 */
@Service
public class BillService {

  private final BillWriter writer;
  private final BillRepository billRepository;

  public BillService(BillWriter writer, BillRepository billRepository) {
    this.writer = writer;
    this.billRepository = billRepository;
  }

  /**
   * Opens a new OPEN bill. Returns the created bill (201 Created path).
   *
   * @throws IllegalArgumentException on invalid tableId
   */
  public BillResponse open(OpenBillRequest request) {
    TenantContext.require();
    return writer.open(request);
  }

  /**
   * Lists bills for a business, filtered by status (default OPEN) and optionally by table.
   *
   * @param businessId the business unit whose bills to list
   * @param status the status filter (OPEN | PAID | CANCELLED)
   * @param tableId optional table filter
   */
  @Transactional(readOnly = true)
  public List<BillSummaryResponse> list(UUID businessId, String status, UUID tableId) {
    TenantContext.require();
    List<BillSummaryView> views =
        tableId != null
            ? billRepository.findSummaryByBusinessIdAndStatusAndTable(businessId, status, tableId)
            : billRepository.findSummaryByBusinessIdAndStatus(businessId, status);
    return views.stream().map(BillService::toSummaryResponse).toList();
  }

  /**
   * Returns the full bill (with lines, modifiers, and live price breakdown) for the given bill id.
   *
   * @throws id.co.nativeapp.restaurant.bill.domain.BillNotFoundException if not found or not
   *     visible to this tenant
   */
  public BillResponse getById(UUID billId) {
    TenantContext.require();
    return writer
        .findById(billId)
        .orElseThrow(
            () -> new id.co.nativeapp.restaurant.bill.domain.BillNotFoundException(billId));
  }

  /**
   * Appends a round of items to an OPEN bill. Returns the updated BillResponse.
   *
   * @throws id.co.nativeapp.restaurant.bill.domain.BillNotFoundException if not found
   * @throws id.co.nativeapp.restaurant.bill.domain.BillNotOpenException if not OPEN
   */
  public BillResponse appendLines(UUID billId, AppendLinesRequest request) {
    TenantContext.require();
    return writer.appendLines(billId, request);
  }

  /**
   * Removes a line from an OPEN bill.
   *
   * @throws id.co.nativeapp.restaurant.bill.domain.BillNotFoundException if not found
   * @throws id.co.nativeapp.restaurant.bill.domain.BillNotOpenException if not OPEN
   * @throws IllegalArgumentException if the line does not belong to this bill
   */
  public void removeLine(UUID billId, UUID lineId) {
    TenantContext.require();
    writer.removeLine(billId, lineId);
  }

  /**
   * Finalises an OPEN bill: deducts stock, records the sale, transitions to PAID. Idempotent on
   * re-pay of a PAID bill.
   *
   * @throws id.co.nativeapp.restaurant.bill.domain.BillNotFoundException if not found
   * @throws id.co.nativeapp.restaurant.bill.domain.BillNotOpenException if not OPEN (and not PAID)
   * @throws id.co.nativeapp.restaurant.menu.domain.InsufficientStockException on stock shortfall
   */
  public BillResponse payBill(UUID billId, PayBillRequest request) {
    TenantContext.require();
    return writer.payBill(billId, request);
  }

  /**
   * Initiates a PENDING gateway (QRIS/CARD) payment for the FULL bill (V38) — mints a {@code
   * payment} row for the check's grand total and reserves every unpaid line against it. Records NO
   * sale; revenue is recognised only when the payment is captured (webhook or manual capture).
   *
   * @throws id.co.nativeapp.restaurant.bill.domain.BillNotFoundException if not found
   * @throws id.co.nativeapp.restaurant.bill.domain.BillNotOpenException if not OPEN
   * @throws IllegalArgumentException if the bill has no unpaid lines or {@code request.payment()}
   *     is missing/not a digital (QRIS/CARD) tender
   * @throws id.co.nativeapp.restaurant.bill.domain.BillLineReservationConflictException on a
   *     concurrent reservation conflict (409; safe to retry)
   */
  public PaymentResponse initiatePendingPayment(UUID billId, PayBillRequest request) {
    TenantContext.require();
    return writer.initiatePendingPayment(billId, request);
  }

  /**
   * Cancels an OPEN bill (no sale, no stock change).
   *
   * @throws id.co.nativeapp.restaurant.bill.domain.BillNotFoundException if not found
   * @throws id.co.nativeapp.restaurant.bill.domain.BillNotOpenException if not OPEN
   */
  public void cancelBill(UUID billId) {
    TenantContext.require();
    writer.cancelBill(billId);
  }

  private static BillSummaryResponse toSummaryResponse(BillSummaryView view) {
    return new BillSummaryResponse(
        view.getId(),
        view.getBusinessId(),
        view.getTableId(),
        view.getGuestLabel(),
        view.getStatus(),
        view.getCurrency().strip(),
        view.getDiscountMinor(),
        view.getRunningTotalMinor(),
        view.getLineCount());
  }
}
