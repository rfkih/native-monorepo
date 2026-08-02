package id.co.nativeapp.restaurant.channel.repository;

import id.co.nativeapp.restaurant.channel.domain.SalesChannel;
import id.co.nativeapp.restaurant.channel.projection.SalesChannelView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link SalesChannel} (ADR 0036 Phase B2).
 *
 * <p>No manual {@code WHERE company_id} — the {@code sales_channel_tenant_isolation} RLS policy
 * (V24) scopes every query to the bound tenant automatically (rule 5). Read paths return the narrow
 * {@link SalesChannelView} projection via native queries (CODE-STRUCTURE.md §3.3); the write path
 * (create/update) loads the full aggregate via the inherited {@code findById}/{@code save}.
 */
public interface SalesChannelRepository extends JpaRepository<SalesChannel, UUID> {

  /** Every sales channel visible to the bound tenant, ordered by code — the POS/admin listing. */
  @Query(
      value =
          """
          SELECT id     AS id,
                 code   AS code,
                 name   AS name,
                 active AS active
            FROM sales_channel
           ORDER BY code ASC
          """,
      nativeQuery = true)
  List<SalesChannelView> findAllViews();

  /** A single sales channel's read view by id. */
  @Query(
      value =
          """
          SELECT id     AS id,
                 code   AS code,
                 name   AS name,
                 active AS active
            FROM sales_channel
           WHERE id = :id
          """,
      nativeQuery = true)
  Optional<SalesChannelView> findViewById(@Param("id") UUID id);

  /**
   * Checkout/pay-time validation (ADR 0036 Phase B2, ONLINE tender): {@code true} only when a
   * channel with this exact (already-normalized) code exists AND is active for the bound tenant —
   * a deactivated or unknown code is rejected the same way by the calling writer ({@code
   * IllegalArgumentException} → {@code 400 invalid-argument}).
   */
  @Query(
      value = "SELECT EXISTS (SELECT 1 FROM sales_channel WHERE code = :code AND active = TRUE)",
      nativeQuery = true)
  boolean existsActiveByCode(@Param("code") String code);

  /**
   * Create-time duplicate-code pre-check (regardless of active status) — a derived query (no
   * {@code @Query}, so the native-query ArchUnit rule does not apply; CODE-STRUCTURE.md §3.3
   * permits derived finders alongside native {@code @Query}s). The {@code uq_sales_channel_code
   * (company_id, code)} constraint (V24) is the race backstop for a genuine concurrent
   * double-create — the pre-check + unique-backstop idiom CODE-STRUCTURE.md §3.2 documents as an
   * accepted alternative to catching {@code DataIntegrityViolationException} in a fresh re-read.
   */
  boolean existsByCode(String code);
}
