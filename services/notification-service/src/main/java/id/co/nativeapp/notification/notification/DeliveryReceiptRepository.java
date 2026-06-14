package id.co.nativeapp.notification.notification;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link DeliveryReceipt}.
 *
 * <p>A thin data port: derived queries only, no business logic, no manual {@code WHERE company_id}
 * — tenant scoping comes solely from the auto-applied RLS GUC (rule 5).
 */
public interface DeliveryReceiptRepository extends JpaRepository<DeliveryReceipt, UUID> {

  /** The terminal receipt for a notification within the bound tenant (RLS-scoped), if any. */
  Optional<DeliveryReceipt> findByNotificationId(UUID notificationId);
}
