package id.co.nativeapp.finance.companyexpense.service;

import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseNotFoundException;
import id.co.nativeapp.finance.companyexpense.dto.CompanyExpenseResponse;
import id.co.nativeapp.finance.companyexpense.projection.CompanyExpenseLineView;
import id.co.nativeapp.finance.companyexpense.projection.CompanyExpenseSummaryView;
import id.co.nativeapp.finance.companyexpense.repository.CompanyExpenseLineRepository;
import id.co.nativeapp.finance.companyexpense.repository.CompanyExpenseRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the company-expense feature: native + projection queries only (CLAUDE.md), mapped to
 * the boundary DTOs. {@code @Transactional(readOnly = true)} so the RLS aspect binds the tenant GUC
 * (an unbound read fails closed to zero rows — rule 5).
 */
@Component
public class CompanyExpenseReader {

  /** List page size cap — the console shows recent expenses, not an export. */
  static final int MAX_LIST = 200;

  private final CompanyExpenseRepository expenseRepository;
  private final CompanyExpenseLineRepository lineRepository;

  public CompanyExpenseReader(
      CompanyExpenseRepository expenseRepository, CompanyExpenseLineRepository lineRepository) {
    this.expenseRepository = expenseRepository;
    this.lineRepository = lineRepository;
  }

  /** Recent expenses, newest first (summaries only — no lines). */
  @Transactional(readOnly = true)
  public List<CompanyExpenseResponse> listRecent(Integer limit) {
    int capped = limit == null ? 50 : Math.min(Math.max(limit, 1), MAX_LIST);
    List<CompanyExpenseResponse> out = new ArrayList<>();
    for (CompanyExpenseSummaryView view : expenseRepository.findRecent(capped)) {
      out.add(toResponse(view, List.of()));
    }
    return out;
  }

  /** One expense with its ingredient lines (empty for GENERAL). */
  @Transactional(readOnly = true)
  public CompanyExpenseResponse getById(UUID expenseId) {
    CompanyExpenseSummaryView view =
        expenseRepository
            .findSummaryById(expenseId)
            .orElseThrow(() -> new CompanyExpenseNotFoundException(expenseId));
    List<CompanyExpenseResponse.LineResponse> lines = new ArrayList<>();
    for (CompanyExpenseLineView line : lineRepository.findViewsByExpenseId(expenseId)) {
      lines.add(
          new CompanyExpenseResponse.LineResponse(
              line.getId(),
              line.getLineNo(),
              line.getIngredientId(),
              line.getIngredientName(),
              line.getQtyBase(),
              line.getValueMinor(),
              line.getDescription()));
    }
    return toResponse(view, lines);
  }

  private static CompanyExpenseResponse toResponse(
      CompanyExpenseSummaryView view, List<CompanyExpenseResponse.LineResponse> lines) {
    return new CompanyExpenseResponse(
        view.getId(),
        view.getExpenseNo(),
        view.getKind(),
        view.getBusinessId(),
        view.getGlHint(),
        view.getDescription(),
        view.getAmountMinor(),
        view.getCurrency(),
        view.getOccurredAt(),
        view.getStatus(),
        lines);
  }
}
