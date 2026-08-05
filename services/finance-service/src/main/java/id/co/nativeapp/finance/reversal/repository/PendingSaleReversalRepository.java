package id.co.nativeapp.finance.reversal.repository;

import id.co.nativeapp.finance.reversal.domain.PendingSaleReversal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Write-path repository for {@link PendingSaleReversal} (full aggregate loads only — no reads). */
public interface PendingSaleReversalRepository extends JpaRepository<PendingSaleReversal, UUID> {

  List<PendingSaleReversal> findBySaleIdAndAppliedAtIsNullOrderByOccurredAtAsc(UUID saleId);

  /**
   * Parks a reversal atomically — one row per reversal EVENT; a redelivery race resolves to a clean
   * no-op instead of a constraint violation poisoning the transaction. RLS {@code WITH CHECK} binds
   * {@code company_id} on the insert (the {@code RevenuePostingWriter} upsert precedent).
   *
   * @return 1 if this call parked the row, 0 if the event was already parked
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  @Modifying
  @Query(
      value =
          """
          INSERT INTO pending_sale_reversal
              (id, sale_id, kind, reversal_event_id, payment_id, amount_minor, currency,
               total_refunded_minor, occurred_at, tender_type, channel, business_id,
               created_at, created_by, updated_at, updated_by, version, company_id)
          VALUES (:id, :saleId, :kind, :reversalEventId, :paymentId, :amountMinor, :currency,
                  :totalRefundedMinor, :occurredAt, :tenderType, :channel, :businessId,
                  now(), :actor, now(), :actor, 0, :companyId)
          ON CONFLICT (company_id, reversal_event_id) DO NOTHING
          """,
      nativeQuery = true)
  int parkIfAbsent(
      @Param("id") UUID id,
      @Param("saleId") UUID saleId,
      @Param("kind") String kind,
      @Param("reversalEventId") UUID reversalEventId,
      @Param("paymentId") UUID paymentId,
      @Param("amountMinor") long amountMinor,
      @Param("currency") String currency,
      @Param("totalRefundedMinor") Long totalRefundedMinor,
      @Param("occurredAt") Instant occurredAt,
      @Param("tenderType") String tenderType,
      @Param("channel") String channel,
      @Param("businessId") UUID businessId,
      @Param("actor") String actor,
      @Param("companyId") String companyId);
}
