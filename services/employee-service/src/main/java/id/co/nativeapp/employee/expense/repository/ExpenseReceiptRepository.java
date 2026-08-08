package id.co.nativeapp.employee.expense.repository;

import id.co.nativeapp.employee.expense.domain.ExpenseReceipt;
import id.co.nativeapp.employee.expense.projection.ReceiptMetaView;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link ExpenseReceipt} aggregate.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC (rule 5). {@link #findMetaByClaimId} is the ONLY read here
 * and it NEVER selects {@code data}; the blob loads exclusively via the inherited {@code findById}
 * on the authenticated serve path ({@code ReceiptReader}, CODE-STRUCTURE §3.3).
 */
public interface ExpenseReceiptRepository extends JpaRepository<ExpenseReceipt, UUID> {

  /**
   * The claim's current receipt METADATA — id/content_type/byte_size/sha256, newest-first so the
   * (structurally-allowed, v1-writer-unused) case of more than one row for a claim still resolves
   * deterministically to the most recently uploaded one. Never selects {@code data}.
   */
  @Query(
      value =
          """
          SELECT id AS id, content_type AS content_type, byte_size AS byte_size,
                 sha256 AS sha256, object_key AS object_key
            FROM expense_receipt
           WHERE claim_id = :claimId
           ORDER BY created_at DESC
           LIMIT 1
          """,
      nativeQuery = true)
  Optional<ReceiptMetaView> findMetaByClaimId(@Param("claimId") UUID claimId);

  /**
   * Deletes every receipt row for a claim — the delete half of "replace on re-upload" ({@code
   * ReceiptWriter#upload}). Mirrors the {@code BudgetLineRepository#deleteByBudgetId} native-
   * delete idiom.
   */
  @Modifying
  @Query(value = "DELETE FROM expense_receipt WHERE claim_id = :claimId", nativeQuery = true)
  void deleteByClaimId(@Param("claimId") UUID claimId);
}
