package id.co.nativeapp.finance.companyexpense.repository;

import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseLine;
import id.co.nativeapp.finance.companyexpense.projection.CompanyExpenseLineView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence port for {@link CompanyExpenseLine}; reads are native + projection (CLAUDE.md). */
public interface CompanyExpenseLineRepository extends JpaRepository<CompanyExpenseLine, UUID> {

  /** One expense's ingredient lines in entry order. */
  @Query(
      value =
          "SELECT id AS id, line_no AS line_no, ingredient_id AS ingredient_id,"
              + " ingredient_name AS ingredient_name, qty_base AS qty_base,"
              + " value_minor AS value_minor"
              + " FROM company_expense_line WHERE expense_id = :expenseId ORDER BY line_no",
      nativeQuery = true)
  List<CompanyExpenseLineView> findViewsByExpenseId(@Param("expenseId") UUID expenseId);
}
