package id.co.nativeapp.notification.notification.repository;

import id.co.nativeapp.notification.notification.domain.Notification;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link Notification} aggregate.
 *
 * <p>A thin data port: derived queries only, no business logic, no manual {@code WHERE company_id}
 * — tenant scoping comes solely from the auto-applied RLS GUC (rule 5). A cross-tenant notification
 * is invisible (empty), so a lookup can only ever return this tenant's rows.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /** The notification created by a given trigger event, within the bound tenant (RLS-scoped). */
  Optional<Notification> findByTriggerEventId(UUID triggerEventId);
}
