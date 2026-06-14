package id.co.nativeapp.servicetemplate.widget;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The template's sample {@link Auditable} entity — the one table a cloned service starts with, to
 * be renamed/replaced for the real bounded context.
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns ({@code
 * created_at}/{@code created_by}, {@code updated_at}/{@code updated_by}, {@code version}, {@code
 * company_id}) and is therefore covered by the {@code widget} RLS policy in the Flyway baseline.
 * {@code ddl-auto=validate} checks this mapping against that migration at startup.
 */
@Entity
@Table(name = "widget")
public class Widget extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String name;

  protected Widget() {
    // for JPA
  }

  public Widget(String name) {
    this.name = name;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
