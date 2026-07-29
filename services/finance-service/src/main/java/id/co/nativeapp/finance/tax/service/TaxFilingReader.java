package id.co.nativeapp.finance.tax.service;

import id.co.nativeapp.finance.tax.domain.TaxFilingNotFoundException;
import id.co.nativeapp.finance.tax.dto.EfakturExportResponse;
import id.co.nativeapp.finance.tax.dto.EfakturLine;
import id.co.nativeapp.finance.tax.dto.TaxFilingHistoryResponse;
import id.co.nativeapp.finance.tax.dto.TaxFilingResponse;
import id.co.nativeapp.finance.tax.projection.EfakturLineView;
import id.co.nativeapp.finance.tax.projection.TaxFilingView;
import id.co.nativeapp.finance.tax.repository.TaxFilingRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-side service for filed PPN returns (Phase 4 Tax / PPN): a filing detail, the filing
 * history, and the e-Faktur CSV export. Native + projection reads throughout (RLS-scoped); maps
 * projections to DTOs / CSV so the {@code tax.projection} types never leak to the controller layer.
 */
@Service
public class TaxFilingReader {

  private final TaxFilingRepository taxFilingRepository;

  public TaxFilingReader(TaxFilingRepository taxFilingRepository) {
    this.taxFilingRepository = Objects.requireNonNull(taxFilingRepository, "taxFilingRepository");
  }

  /** One filing by id, or {@link TaxFilingNotFoundException} (→ 404) if not in the bound tenant. */
  @Transactional(readOnly = true)
  public TaxFilingResponse detail(UUID id) {
    return taxFilingRepository
        .findViewById(id)
        .map(TaxFilingReader::toResponse)
        .orElseThrow(() -> new TaxFilingNotFoundException(id));
  }

  /** The filing history for the bound tenant, most-recent period first. */
  @Transactional(readOnly = true)
  public TaxFilingHistoryResponse history() {
    List<TaxFilingResponse> filings =
        taxFilingRepository.findAllViews().stream().map(TaxFilingReader::toResponse).toList();
    return new TaxFilingHistoryResponse(filings);
  }

  /**
   * The e-Faktur export for {@code period} — the period's output tax invoices (DPP, PPN, total, in
   * minor units + currency). The console renders these to a CSV download (client-side, like the
   * other finance exports). ILLUSTRATIVE flat layout, NOT the certified DJP e-Faktur schema (ADR
   * 0017 SME gate). Empty {@code lines} when the period has no output tax invoices.
   */
  @Transactional(readOnly = true)
  public EfakturExportResponse efaktur(String period) {
    Objects.requireNonNull(period, "period");
    List<EfakturLine> lines =
        taxFilingRepository.findEfakturLines(period).stream()
            .map(TaxFilingReader::toEfakturLine)
            .toList();
    return new EfakturExportResponse(period, lines);
  }

  private static EfakturLine toEfakturLine(EfakturLineView v) {
    return new EfakturLine(
        v.getInvoiceNumber(),
        v.getIssueDate(),
        v.getCustomerName(),
        v.getCustomerTaxId(),
        v.getDppMinor(),
        v.getPpnMinor(),
        v.getTotalMinor(),
        v.getCurrency().strip());
  }

  private static TaxFilingResponse toResponse(TaxFilingView v) {
    return new TaxFilingResponse(
        v.getId(),
        v.getPeriod(),
        v.getTaxType(),
        v.getStatus(),
        v.getCurrency().strip(),
        v.getOutputVatMinor(),
        v.getInputVatMinor(),
        v.getNetMinor(),
        v.getNetDirection(),
        v.getFilingEntryId(),
        v.getSettlementEntryId(),
        v.getFiledAt(),
        v.getSettledAt());
  }
}
