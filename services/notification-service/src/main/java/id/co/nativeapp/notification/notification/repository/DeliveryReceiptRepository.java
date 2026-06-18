package id.co.nativeapp.notification.notification.repository;

import id.co.nativeapp.notification.notification.domain.DeliveryReceipt;
import id.co.nativeapp.notification.notification.projection.DeliveryReceiptView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for the {@link DeliveryReceipt}.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC on every {@code @Transactional} method (rule 5). Native
 * queries run on that same RLS-scoped connection, so projecting columns by hand does NOT weaken
 * tenant isolation.
 *
 * <p><strong>Read paths use a native query + projection (CLAUDE.md convention — "native-query
 * aliases snake_case; map via projection interfaces").</strong> {@link #findAllViews()} fetches
 * only the five business columns the read path needs ({@link DeliveryReceiptView}) rather than
 * {@code SELECT *} of the full {@code Auditable} row — narrower I/O, and the entity is loaded only
 * on the write path.
 *
 * <p><strong>Write-path load kept on the entity path.</strong> The derived {@link
 * #findByNotificationId(UUID)} carries no {@code @Query} annotation and returns the full {@link
 * DeliveryReceipt} entity for mutation. The native-query ArchUnit rule does not apply to derived
 * queries (no {@code @Query} annotation).
 */
public interface DeliveryReceiptRepository extends JpaRepository<DeliveryReceipt, UUID> {

  /**
   * The terminal receipt for a notification within the bound tenant (RLS-scoped), if any.
   *
   * <p><strong>Write-path entity load — deliberately NOT a projection.</strong> Kept as a derived
   * query returning the full entity for any caller that needs to inspect or mutate the aggregate.
   * No {@code @Query} annotation, so the native-query ArchUnit rule does not apply.
   */
  Optional<DeliveryReceipt> findByNotificationId(UUID notificationId);

  /**
   * Every delivery receipt visible to the bound tenant, projected to {@link DeliveryReceiptView}
   * and ordered deterministically. No {@code WHERE company_id} — the result set is constrained
   * solely by the auto-applied RLS policy (rule 5).
   *
   * <p>Used on the pure display read path ({@link
   * id.co.nativeapp.notification.notification.service.NotificationReader}); the entity is never
   * loaded, never mutated, never saved. The five selected columns are exactly what the read path
   * needs.
   */
  @Query(
      value =
          """
          SELECT dr.id              AS id,
                 dr.notification_id AS notification_id,
                 dr.status          AS status,
                 dr.provider_ref    AS provider_ref,
                 dr.delivered_at    AS delivered_at
            FROM delivery_receipt dr
           ORDER BY dr.delivered_at, dr.id
          """,
      nativeQuery = true)
  List<DeliveryReceiptView> findAllViews();
}
