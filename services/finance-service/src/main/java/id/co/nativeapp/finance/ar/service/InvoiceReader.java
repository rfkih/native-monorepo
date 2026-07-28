package id.co.nativeapp.finance.ar.service;

import id.co.nativeapp.finance.ar.domain.InvoiceNotFoundException;
import id.co.nativeapp.finance.ar.dto.InvoiceDetailResponse;
import id.co.nativeapp.finance.ar.dto.InvoiceDetailResponse.LineResponse;
import id.co.nativeapp.finance.ar.dto.InvoiceDetailResponse.PaymentResponse;
import id.co.nativeapp.finance.ar.dto.InvoiceSummaryResponse;
import id.co.nativeapp.finance.ar.projection.InvoiceDetailView;
import id.co.nativeapp.finance.ar.repository.InvoiceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads invoices for the bound tenant — list + detail. {@code @Transactional(readOnly = true)} so
 * the proxy + auto-RLS aspect engage; the reads carry no manual {@code WHERE company_id} (RLS
 * scopes them). Projection types never leave this layer.
 */
@Service
public class InvoiceReader {

  private final InvoiceRepository invoiceRepository;

  public InvoiceReader(InvoiceRepository invoiceRepository) {
    this.invoiceRepository = invoiceRepository;
  }

  /**
   * The invoice list, with optional filters. {@code status} / {@code period} ({@code YYYY-MM} issue
   * month) are passed through as-is; {@code customerId} is stringified for the nullable-uuid cast.
   */
  @Transactional(readOnly = true)
  public List<InvoiceSummaryResponse> list(String status, UUID customerId, String period) {
    String customerIdParam = customerId == null ? null : customerId.toString();
    return invoiceRepository.findSummaries(status, customerIdParam, period).stream()
        .map(
            v ->
                new InvoiceSummaryResponse(
                    v.getId(),
                    v.getInvoiceNumber(),
                    v.getCustomerId(),
                    v.getCustomerName(),
                    v.getStatus(),
                    v.getIssueDate(),
                    v.getDueDate(),
                    v.getCurrency().strip(),
                    v.getTotalMinor(),
                    v.getPaidMinor(),
                    v.getTotalMinor() - v.getPaidMinor()))
        .toList();
  }

  /**
   * The full invoice detail (header + lines + payments).
   *
   * @throws InvoiceNotFoundException if not in the bound tenant (RLS-scoped)
   */
  @Transactional(readOnly = true)
  public InvoiceDetailResponse detail(UUID id) {
    InvoiceDetailView header =
        invoiceRepository.findDetail(id).orElseThrow(() -> new InvoiceNotFoundException(id));

    List<LineResponse> lines =
        invoiceRepository.findLines(id).stream()
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
        invoiceRepository.findPayments(id).stream()
            .map(
                p ->
                    new PaymentResponse(
                        p.getId(),
                        p.getAmountMinor(),
                        p.getCurrency().strip(),
                        p.getPaidAt(),
                        p.getMethod()))
            .toList();

    return new InvoiceDetailResponse(
        header.getId(),
        header.getInvoiceNumber(),
        header.getCustomerId(),
        header.getCustomerName(),
        header.getStatus(),
        header.getIssueDate(),
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
