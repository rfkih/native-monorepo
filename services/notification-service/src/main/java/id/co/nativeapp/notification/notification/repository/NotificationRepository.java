package id.co.nativeapp.notification.notification.repository;

import id.co.nativeapp.notification.notification.domain.Notification;
import id.co.nativeapp.notification.notification.projection.NotificationView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for the {@link Notification} aggregate.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC on every {@code @Transactional} method (rule 5). Native
 * queries run on that same RLS-scoped connection, so projecting columns by hand does NOT weaken
 * tenant isolation.
 *
 * <p><strong>Read paths use a native query + projection (CLAUDE.md convention — "native-query
 * aliases snake_case; map via projection interfaces").</strong> {@link #findAllViews()} fetches
 * only the five business columns the read path needs ({@link NotificationView}) rather than {@code
 * SELECT *} of the full {@code Auditable} row — narrower I/O, and the entity is loaded only on the
 * write path (the inherited {@code save}/{@code saveAndFlush}, which legitimately needs the whole
 * aggregate to mutate it).
 *
 * <p><strong>Write-path loads kept on the entity path.</strong> The inherited {@link
 * JpaRepository#findById}/{@link JpaRepository#save} and the derived {@link
 * #findByTriggerEventId(UUID)} carry no {@code @Query} annotation; they return the full {@link
 * Notification} entity so the write flow ({@link
 * id.co.nativeapp.notification.notification.service.NotificationDeliveryWriter}) can mutate it
 * (e.g. {@code markSent()}/{@code markFailed()}) and save it back. The native-query {@link
 * id.co.nativeapp.tenant.RlsAutoApplyAspect ArchUnit} rule does not apply to derived queries (no
 * {@code @Query} annotation). {@link #findByTriggerEventId} is the idempotency lookup — it needs
 * the full entity because the result may be mutated.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /**
   * The notification created by a given trigger event, within the bound tenant (RLS-scoped).
   *
   * <p><strong>Write-path entity load — deliberately NOT a projection.</strong> The result may be
   * mutated (status transition) and saved back. No {@code @Query} annotation, so the native-query
   * {@link RlsAutoApplyAspect ArchUnit} rule does not apply; Spring Data's derived query is used
   * instead.
   */
  Optional<Notification> findByTriggerEventId(UUID triggerEventId);

  /**
   * Every notification visible to the bound tenant, projected to {@link NotificationView} and
   * ordered deterministically. No {@code WHERE company_id} — the result set is constrained solely
   * by the auto-applied RLS policy (rule 5).
   *
   * <p>Used on the pure display read path ({@link
   * id.co.nativeapp.notification.notification.service.NotificationReader}); the entity is never
   * loaded, never mutated, never saved. The five selected columns are exactly what the read path
   * needs — {@code recipient} is intentionally excluded (PII-adjacent; never needed by a read path;
   * write-path entity loads carry it naturally).
   */
  @Query(
      value =
          """
          SELECT n.id               AS id,
                 n.channel          AS channel,
                 n.status           AS status,
                 n.subject          AS subject,
                 n.trigger_event_id AS trigger_event_id
            FROM notification n
           ORDER BY n.created_at, n.id
          """,
      nativeQuery = true)
  List<NotificationView> findAllViews();
}
