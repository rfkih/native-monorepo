package id.co.nativeapp.org.user.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * A {@code user_page_grant} row — one console page a login (Keycloak sub) may open. SUBTRACTIVE,
 * UI-level gating: zero rows for a user = the full role surface (grandfather); one or more rows =
 * restricted to exactly those page keys. Owner/manager bypass grants entirely (console-side).
 *
 * <p>Holds only ids/keys (no PII, rule 6 N/A). Extends {@link Auditable} for the audit + tenancy
 * columns and the {@code user_page_grant} RLS policy (rule 4 + rule 5). A logical delete flips
 * {@link #active} (history preserved); re-granting reopens the row (unique-set constraint).
 */
@Entity
@Table(name = "user_page_grant")
public class UserPageGrant extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, length = 64, updatable = false)
  private String userId;

  @Column(name = "page_key", nullable = false, length = 64, updatable = false)
  private String pageKey;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected UserPageGrant() {
    // for JPA
  }

  /** Creates an active page grant with a freshly generated id. */
  public UserPageGrant(String userId, String pageKey) {
    this.id = UUID.randomUUID();
    this.userId = Objects.requireNonNull(userId, "userId");
    this.pageKey = Objects.requireNonNull(pageKey, "pageKey");
    this.active = true;
  }

  /** Logical delete — the row is kept (history) but no longer grants the page. */
  public void deactivate() {
    this.active = false;
  }

  /** Re-grant a previously removed page (reopen the existing row). */
  public void reactivate() {
    this.active = true;
  }

  public UUID getId() {
    return id;
  }

  public String getUserId() {
    return userId;
  }

  public String getPageKey() {
    return pageKey;
  }

  public boolean isActive() {
    return active;
  }
}
