package id.co.nativeapp.finance.platform.repository;

import id.co.nativeapp.finance.platform.domain.PlatformSettlement;
import id.co.nativeapp.finance.platform.projection.PlatformSettlementView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link PlatformSettlement} (ADR 0036 Phase C). No manual {@code WHERE
 * company_id} — RLS applies the tenant scope (rule 5). The replay probe loads the full entity (the
 * PayrollSettlementRepository idiom — the write path needs the whole row to verify the payload);
 * history reads use the narrow native projection.
 */
public interface PlatformSettlementRepository extends JpaRepository<PlatformSettlement, UUID> {

  /** Replay probe — the settlement previously posted under this Idempotency-Key, if any. */
  Optional<PlatformSettlement> findByIdempotencyKey(String idempotencyKey);

  /** A channel's settlement history, most recent first (idx_platform_settlement_history). */
  @Query(
      value =
          """
          SELECT s.id           AS id,
                 s.channel_code AS channel_code,
                 s.gross_minor  AS gross_minor,
                 s.net_minor    AS net_minor,
                 s.fee_minor    AS fee_minor,
                 s.currency     AS currency,
                 s.settled_at   AS settled_at
            FROM platform_settlement s
           WHERE (:channelCode IS NULL OR s.channel_code = :channelCode)
           ORDER BY s.settled_at DESC
           LIMIT 100
          """,
      nativeQuery = true)
  List<PlatformSettlementView> findHistoryViews(@Param("channelCode") String channelCode);
}
