package id.co.nativeapp.finance.tax.dto;

import java.util.List;

/** The PPN filing history for the bound tenant (Phase 4 Tax / PPN), most-recent period first. */
public record TaxFilingHistoryResponse(List<TaxFilingResponse> filings) {}
