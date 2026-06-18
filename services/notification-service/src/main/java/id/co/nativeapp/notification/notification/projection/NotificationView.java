package id.co.nativeapp.notification.notification.projection;

import java.util.UUID;

/**
 * Read projection over the {@code notification} row — only the columns a read path needs, never the
 * full {@link id.co.nativeapp.notification.notification.domain.Notification} entity with its {@code
 * Auditable} bookkeeping.
 *
 * <p>Backs the native read query on {@link
 * id.co.nativeapp.notification.notification.repository.NotificationRepository} ({@code
 * findAllViews}). Snake_case native-query aliases map to these accessors via Spring Data's
 * projection-interface convention (CLAUDE.md "native-query aliases snake_case; map via projection
 * interfaces"), so the list read path fetches a narrow column set instead of {@code SELECT *} of
 * the full {@code Auditable} entity. Lives in its own {@code projection} package — a read model is
 * neither the write-side {@code domain} entity nor a request/response {@code dto}.
 *
 * <p>The {@code recipient} column (email address / device token) is intentionally excluded: while
 * it is not converter-backed (no {@code AttributeConverter} encrypts it), it is PII-adjacent and is
 * never needed by a read path — so it is kept off the projection to prevent accidental exposure.
 * Write-path loads that do need {@code recipient} go through the entity ({@code
 * findByTriggerEventId} / {@code findById}), which is correct since those paths mutate the
 * aggregate.
 */
public interface NotificationView {

  UUID getId();

  String getChannel();

  String getStatus();

  String getSubject();

  UUID getTriggerEventId();
}
