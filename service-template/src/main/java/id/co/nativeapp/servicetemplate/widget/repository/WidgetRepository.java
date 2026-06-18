package id.co.nativeapp.servicetemplate.widget.repository;

import id.co.nativeapp.servicetemplate.widget.domain.Widget;
import id.co.nativeapp.servicetemplate.widget.projection.WidgetView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for {@link Widget}.
 *
 * <p>Deliberately carries <em>no</em> manual tenant filtering and no call to apply the tenant GUC:
 * every Spring Data method is transactional, so {@link RlsAutoApplyAspect} sets {@code
 * app.current_tenant} on the connection automatically and the PostgreSQL RLS policy restricts
 * results to the bound company. This is the point of the template — correctness by default, even
 * for the developer who forgets.
 *
 * <p><strong>Queries are native + projection (CLAUDE.md convention).</strong> The read path {@link
 * #findAllViews()} is a native query returning the narrow {@link WidgetView} projection — only the
 * columns a read needs, never {@code SELECT *} of the entity; the {@code
 * repositoryQueriesAreNative} ArchUnit rule fails the build on a JPQL {@code @Query}. The full
 * entity is loaded only on the write path (the inherited {@code findById}/{@code save}, which needs
 * the whole aggregate).
 */
public interface WidgetRepository extends JpaRepository<Widget, Long> {

  /**
   * All widgets visible to the bound tenant, projected to {@link WidgetView}. No {@code WHERE
   * company_id} — the result set is constrained solely by the auto-applied RLS policy.
   */
  @Query(
      value =
          """
          SELECT w.id   AS id,
                 w.name AS name
            FROM widget w
           ORDER BY w.name, w.id
          """,
      nativeQuery = true)
  List<WidgetView> findAllViews();
}
