package id.co.nativeapp.notification.notification.service;

import id.co.nativeapp.notification.notification.domain.DeliveryReceipt;
import id.co.nativeapp.notification.notification.domain.Notification;
import id.co.nativeapp.notification.notification.repository.DeliveryReceiptRepository;
import id.co.nativeapp.notification.notification.repository.NotificationRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side service for notifications + receipts — the tenant-scoped query seam.
 *
 * <p>Each read runs in a {@code @Transactional} unit so the {@link
 * id.co.nativeapp.tenant.RlsAutoApplyAspect} sets the tenant GUC for the bound {@link
 * id.co.nativeapp.tenant.TenantContext}; the result set is then constrained SOLELY by the
 * auto-applied RLS policy (rule 5) — there is NO {@code WHERE company_id} anywhere, modelling a
 * developer who forgets the tenant and proving they are still protected. A future query API would
 * sit on top of this reader.
 */
@Service
public class NotificationReader {

  private final NotificationRepository notificationRepository;
  private final DeliveryReceiptRepository receiptRepository;

  public NotificationReader(
      NotificationRepository notificationRepository, DeliveryReceiptRepository receiptRepository) {
    this.notificationRepository = notificationRepository;
    this.receiptRepository = receiptRepository;
  }

  /** All notifications visible to the bound tenant (RLS-scoped; no manual {@code WHERE}). */
  @Transactional(readOnly = true)
  public List<Notification> findAllNotificationsForCurrentTenant() {
    return notificationRepository.findAll();
  }

  /** All delivery receipts visible to the bound tenant (RLS-scoped; no manual {@code WHERE}). */
  @Transactional(readOnly = true)
  public List<DeliveryReceipt> findAllReceiptsForCurrentTenant() {
    return receiptRepository.findAll();
  }
}
