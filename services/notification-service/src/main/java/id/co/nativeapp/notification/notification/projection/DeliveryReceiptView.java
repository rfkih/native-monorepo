package id.co.nativeapp.notification.notification.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection over the {@code delivery_receipt} row — only the columns a read path needs, never
 * the full {@link id.co.nativeapp.notification.notification.domain.DeliveryReceipt} entity with its
 * {@code Auditable} bookkeeping.
 *
 * <p>Backs the native read query on {@link
 * id.co.nativeapp.notification.notification.repository.DeliveryReceiptRepository} ({@code
 * findAllViews}). Snake_case native-query aliases map to these accessors via Spring Data's
 * projection-interface convention (CLAUDE.md "native-query aliases snake_case; map via projection
 * interfaces"), so the list read path fetches a narrow column set instead of {@code SELECT *} of
 * the full {@code Auditable} entity. Lives in its own {@code projection} package — a read model is
 * neither the write-side {@code domain} entity nor a request/response {@code dto}.
 *
 * <p>Used ONLY on the display read path ({@link
 * id.co.nativeapp.notification.notification.service.NotificationReader}). Write-path loads that
 * mutate the receipt aggregate go through the inherited {@code JpaRepository} CRUD methods.
 */
public interface DeliveryReceiptView {

  UUID getId();

  UUID getNotificationId();

  String getStatus();

  String getProviderRef();

  Instant getDeliveredAt();
}
