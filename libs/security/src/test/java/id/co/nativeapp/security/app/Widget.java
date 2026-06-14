package id.co.nativeapp.security.app;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A minimal RLS-scoped aggregate for the #16 defense-in-depth proof. It extends {@link Auditable}
 * (so it carries {@code company_id} and is governed by the same row-level-security policy every
 * Native table is) and holds nothing but a name. Reading it through the secured endpoint must
 * return only the rows belonging to the tenant bound from the validated JWT.
 */
@Entity
@Table(name = "widget")
public class Widget extends Auditable {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "name", nullable = false)
  private String name;

  protected Widget() {
    // for JPA
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }
}
