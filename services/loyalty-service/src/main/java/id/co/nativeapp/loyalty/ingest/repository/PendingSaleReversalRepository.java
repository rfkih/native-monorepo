package id.co.nativeapp.loyalty.ingest.repository;

import id.co.nativeapp.loyalty.ingest.domain.PendingSaleReversal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Write-path repository for {@link PendingSaleReversal} (full aggregate loads only — no reads). */
public interface PendingSaleReversalRepository extends JpaRepository<PendingSaleReversal, UUID> {

  Optional<PendingSaleReversal> findBySaleId(UUID saleId);

  /**
   * Parks a reversal atomically: the FIRST reversal event for a sale wins; a concurrent or later
   * second one (a void followed by a refund — full-reversal-only, review S2) is a clean no-op
   * instead of a constraint violation that would poison the transaction. RLS {@code WITH CHECK}
   * still binds {@code company_id} on the insert (the {@code RevenuePostingWriter} upsert
   * precedent).
   *
   * @return 1 if this call parked the row, 0 if one already existed for the sale
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO pending_sale_reversal
              (id, sale_id, reversal_event_id, reversal_occurred_at,
               created_at, created_by, updated_at, updated_by, version, company_id)
          VALUES (:id, :saleId, :reversalEventId, :reversalOccurredAt,
                  now(), :actor, now(), :actor, 0, :companyId)
          ON CONFLICT (company_id, sale_id) DO NOTHING
          """,
      nativeQuery = true)
  int parkIfAbsent(
      @Param("id") UUID id,
      @Param("saleId") UUID saleId,
      @Param("reversalEventId") UUID reversalEventId,
      @Param("reversalOccurredAt") Instant reversalOccurredAt,
      @Param("actor") String actor,
      @Param("companyId") String companyId);
}
