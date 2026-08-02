package id.co.nativeapp.employee.expense.service;

import id.co.nativeapp.employee.expense.dto.ExpenseCategoryResponse;
import id.co.nativeapp.employee.expense.projection.ExpenseCategoryView;
import id.co.nativeapp.employee.expense.repository.ExpenseCategoryRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The read side for the expense-category catalog — active categories only (the v1 picker). */
@Service
public class ExpenseCategoryReader {

  private final ExpenseCategoryRepository repository;

  public ExpenseCategoryReader(ExpenseCategoryRepository repository) {
    this.repository = repository;
  }

  /** Active categories, name-ordered. */
  @Transactional(readOnly = true)
  public List<ExpenseCategoryResponse> list() {
    return repository.findAllActiveOrderedByName().stream()
        .map(ExpenseCategoryReader::toResponse)
        .toList();
  }

  private static ExpenseCategoryResponse toResponse(ExpenseCategoryView v) {
    return new ExpenseCategoryResponse(
        v.getId(),
        v.getName(),
        v.getGlHint(),
        Boolean.TRUE.equals(v.getTaxable()),
        Boolean.TRUE.equals(v.getActive()));
  }
}
