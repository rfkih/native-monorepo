package id.co.nativeapp.finance.platform.repository;

import id.co.nativeapp.finance.platform.domain.PlatformSettlement;
import id.co.nativeapp.finance.platform.projection.PlatformSettlementSummaryView;
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

  /**
   * The per-channel settlement summary for a {@code YYYY-MM} period ({@code GET
   * /api/v1/platform-settlements/summary}) — one row per {@code (channel_code, currency)} bucket:
   * settled gross/fee/net totals (plain {@code SUM}s, never a float) and settlement count. The
   * month bucket is the OUTLET-LOCAL calendar month: {@code settled_at} is {@code TIMESTAMPTZ}, so
   * it is shifted to {@code Asia/Jakarta} BEFORE {@code to_char} — a UTC session TZ would bucket a
   * payout stamped 00:00–07:00 WIB on the 1st into the prior month. Mirrors the {@code
   * Asia/Jakarta} business-date convention (NOT the DATE-column {@code BillRepository} idiom, which
   * is timezone-immune). RLS-scoped automatically (rule 5) — no manual {@code company_id}
   * predicate, matching this repository's other native queries.
   */
  @Query(
      value =
          """
          SELECT s.channel_code      AS channel_code,
                 SUM(s.gross_minor)  AS settled_gross_minor,
                 SUM(s.fee_minor)    AS fee_minor,
                 SUM(s.net_minor)    AS net_minor,
                 COUNT(*)            AS settlement_count,
                 s.currency          AS currency
            FROM platform_settlement s
           WHERE to_char(s.settled_at AT TIME ZONE 'Asia/Jakarta', 'YYYY-MM') = :period
           GROUP BY s.channel_code, s.currency
           ORDER BY s.channel_code
          """,
      nativeQuery = true)
  List<PlatformSettlementSummaryView> findSummary(@Param("period") String period);
}
