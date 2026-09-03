package id.co.nativeapp.finance.companyexpense.repository;

import id.co.nativeapp.finance.companyexpense.domain.CompanyExpense;
import id.co.nativeapp.finance.companyexpense.projection.CompanyExpenseSummaryView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence port for {@link CompanyExpense}. Reads are native + projection (CLAUDE.md); the full
 * entity loads only on the write path (findById/save). All queries run under the tenant RLS policy
 * (V58) bound by the calling {@code @Transactional} writer/reader.
 */
public interface CompanyExpenseRepository extends JpaRepository<CompanyExpense, UUID> {

  /** The replay probe for a client-supplied Idempotency-Key (RLS scopes it to the tenant). */
  Optional<CompanyExpense> findByIdempotencyKey(String idempotencyKey);

  /** The list read path: newest first, capped by the caller-supplied limit. */
  @Query(
      value =
          "SELECT id AS id, expense_no AS expense_no, kind AS kind, business_id AS business_id,"
              + " gl_hint AS gl_hint, description AS description, amount_minor AS amount_minor,"
              + " currency AS currency, occurred_at AS occurred_at, status AS status"
              + " FROM company_expense ORDER BY occurred_at DESC, id DESC LIMIT :limit",
      nativeQuery = true)
  List<CompanyExpenseSummaryView> findRecent(@Param("limit") int limit);

  /** One expense's summary columns (detail header), RLS-scoped. */
  @Query(
      value =
          "SELECT id AS id, expense_no AS expense_no, kind AS kind, business_id AS business_id,"
              + " gl_hint AS gl_hint, description AS description, amount_minor AS amount_minor,"
              + " currency AS currency, occurred_at AS occurred_at, status AS status"
              + " FROM company_expense WHERE id = :id",
      nativeQuery = true)
  Optional<CompanyExpenseSummaryView> findSummaryById(@Param("id") UUID id);
}
