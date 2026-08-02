package id.co.nativeapp.restaurant.channel.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code sales_channel} aggregate (ADR 0036 Phase B2) — a company-managed catalog entry for an
 * online-platform sales channel (e.g. {@code GOFOOD}, {@code GRABFOOD}) that a POS checkout can
 * ring an {@code ONLINE}-tender sale through.
 *
 * <p>{@code code} is IMMUTABLE once created — no setter is exposed. The writer normalizes it to
 * uppercase/trim BEFORE construction; this constructor stores whatever it is given verbatim
 * (mirrors {@code Coupon}'s "already normalized" contract — normalization is the service's job, not
 * the entity's). {@code name} is editable; {@code active} is a soft-deactivate flag — a channel
 * already referenced by historical {@code sale}/{@code payment} rows is never hard-deleted (V24
 * migration header); deactivating it only stops it being offered as a NEW selection at payment.
 *
 * <p>Extends {@link Auditable} (rule 4); covered by the {@code sales_channel_tenant_isolation} RLS
 * policy (rule 5, V24).
 */
@Entity
@Table(name = "sales_channel")
public class SalesChannel extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "code", nullable = false, updatable = false, length = 32)
  private String code;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected SalesChannel() {
    // for JPA
  }

  /**
   * Creates a new sales channel with a freshly generated id, active by default.
   *
   * @param code the ALREADY uppercase/trim-normalized code (the writer, not this constructor,
   *     normalizes — mirrors {@code Coupon}'s contract)
   * @param name the display name
   */
  public SalesChannel(String code, String name) {
    this.id = UUID.randomUUID();
    this.code = requireNonBlank(code, "code");
    this.name = requireNonBlank(name, "name");
    this.active = true;
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  public UUID getId() {
    return id;
  }

  /** The immutable channel code (e.g. {@code GOFOOD}) — no setter, see class javadoc. */
  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  /** Renames the channel's display name. {@code code} stays immutable — see class javadoc. */
  public void rename(String name) {
    this.name = requireNonBlank(name, "name");
  }

  public boolean isActive() {
    return active;
  }

  /** Soft-activates/deactivates the channel — never a hard delete (see class javadoc). */
  public void setActive(boolean active) {
    this.active = active;
  }
}
