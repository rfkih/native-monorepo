package id.co.nativeapp.finance.ap.service;

import id.co.nativeapp.finance.ap.domain.BillNotFoundException;
import id.co.nativeapp.finance.ap.dto.BillDetailResponse;
import id.co.nativeapp.finance.ap.dto.BillDetailResponse.LineResponse;
import id.co.nativeapp.finance.ap.dto.BillDetailResponse.PaymentResponse;
import id.co.nativeapp.finance.ap.dto.BillSummaryResponse;
import id.co.nativeapp.finance.ap.projection.BillDetailView;
import id.co.nativeapp.finance.ap.repository.BillRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads bills for the bound tenant — list + detail. {@code @Transactional(readOnly = true)} so the
 * proxy + auto-RLS aspect engage; the reads carry no manual {@code WHERE company_id} (RLS scopes
 * them). Projection types never leave this layer.
 */
@Service
public class BillReader {

  private final BillRepository billRepository;

  public BillReader(BillRepository billRepository) {
    this.billRepository = billRepository;
  }

  /**
   * The bill list, with optional filters. {@code status} / {@code period} ({@code YYYY-MM} bill
   * month) are passed through as-is; {@code vendorId} is stringified for the nullable-uuid cast.
   */
  @Transactional(readOnly = true)
  public List<BillSummaryResponse> list(String status, UUID vendorId, String period) {
    String vendorIdParam = vendorId == null ? null : vendorId.toString();
    return billRepository.findSummaries(status, vendorIdParam, period).stream()
        .map(
            v ->
                new BillSummaryResponse(
                    v.getId(),
                    v.getBillNumber(),
                    v.getVendorId(),
                    v.getVendorName(),
                    v.getStatus(),
                    v.getBillDate(),
                    v.getDueDate(),
                    v.getCurrency().strip(),
                    v.getTotalMinor(),
                    v.getPaidMinor(),
                    v.getTotalMinor() - v.getPaidMinor()))
        .toList();
  }

  /**
   * The full bill detail (header + lines + payments).
   *
   * @throws BillNotFoundException if not in the bound tenant (RLS-scoped)
   */
  @Transactional(readOnly = true)
  public BillDetailResponse detail(UUID id) {
    BillDetailView header =
        billRepository.findDetail(id).orElseThrow(() -> new BillNotFoundException(id));

    List<LineResponse> lines =
        billRepository.findLines(id).stream()
            .map(
                l ->
                    new LineResponse(
                        l.getLineNo(),
                        l.getDescription(),
                        l.getQuantity(),
                        l.getUnitPriceMinor(),
                        l.getLineTotalMinor()))
            .toList();

    List<PaymentResponse> payments =
        billRepository.findPayments(id).stream()
            .map(
                p ->
                    new PaymentResponse(
                        p.getId(),
                        p.getAmountMinor(),
                        p.getCurrency().strip(),
                        p.getPaidAt(),
                        p.getMethod()))
            .toList();

    return new BillDetailResponse(
        header.getId(),
        header.getBillNumber(),
        header.getVendorId(),
        header.getVendorName(),
        header.getStatus(),
        header.getBillDate(),
        header.getDueDate(),
        header.getCurrency().strip(),
        header.getSubtotalMinor(),
        header.getTaxMinor(),
        header.getTotalMinor(),
        header.getPaidMinor(),
        header.getTotalMinor() - header.getPaidMinor(),
        header.getUsesIllustrativeRules(),
        lines,
        payments);
  }
}
