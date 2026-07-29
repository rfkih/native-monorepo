package id.co.nativeapp.finance.assets.repository;

import id.co.nativeapp.finance.assets.domain.Deferral;
import id.co.nativeapp.finance.assets.projection.DeferralView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for {@link Deferral} (Phase 6). RLS scopes all access (rule 5). The run
 * writer loads the aggregates (inherited {@code findAll}); the list READ path is native + projection
 * with the amortized-to-date roll-up.
 */
public interface DeferralRepository extends JpaRepository<Deferral, UUID> {

  /**
   * The deferral created by a prior attempt with this Idempotency-Key (RLS-scoped) — the write-path
   * replay probe: a retried create returns it instead of double-posting the opening entry.
   */
  Optional<Deferral> findByIdempotencyKey(String idempotencyKey);

  /**
   * The deferral list: each deferral with the amount amortized to date (Σ run-line amounts) —
   * remaining = total − amortized, computed by the reader. RLS-scoped; bounded {@code LIMIT}.
   */
  @Query(
      value =
          """
          SELECT d.id                               AS id,
                 d.kind                             AS kind,
                 d.description                      AS description,
                 d.total_minor                      AS total_minor,
                 d.months                           AS months,
                 d.start_period                     AS start_period,
                 d.currency                         AS currency,
                 COALESCE(SUM(arl.amount_minor), 0) AS amortized_minor
            FROM deferral d
            LEFT JOIN amortization_run_line arl
                   ON arl.item_type = 'DEFERRAL' AND arl.item_id = d.id
           GROUP BY d.id, d.kind, d.description, d.total_minor, d.months, d.start_period, d.currency
           ORDER BY d.start_period DESC, d.description
           LIMIT 500
          """,
      nativeQuery = true)
  List<DeferralView> findAllViews();
}
