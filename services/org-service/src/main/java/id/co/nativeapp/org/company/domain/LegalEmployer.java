package id.co.nativeapp.org.company.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code legal_employer} aggregate — the legal employing entity org-service owns. A company has
 * one or more legal employers; in the minimal bootstrap exactly one is created per company, with
 * its id equal to the company id (so the historical {@code legal_employer_id == company_id}
 * invariant of the M1.2 slice still holds), but it is now a real, first-class row rather than an
 * implied identity.
 *
 * <p>Downstream HR (employee-service) anchors its "concurrent assignments must resolve to the same
 * legal_employer" invariant on this entity, so it is modelled here as the employing-entity boundary
 * even though the minimal slice has one per company.
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns ({@code
 * created_at}/{@code created_by}, {@code updated_at}/{@code updated_by}, {@code version}, {@code
 * company_id}) and is covered by the {@code legal_employer} RLS policy in the Flyway migration
 * (rule 4 + rule 5). It validates its own invariants on construction — the aggregate owns its
 * invariants (CLAUDE.md).
 */
@Entity
@Table(name = "legal_employer")
public class LegalEmployer extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false)
  private String name;

  protected LegalEmployer() {
    // for JPA
  }

  /**
   * Creates a legal employer with the given id and name.
   *
   * @param id the legal-employer id (in the bootstrap, equal to the owning company id)
   * @param name the legal-employer name; must be non-blank
   */
  public LegalEmployer(UUID id, String name) {
    this.id = Objects.requireNonNull(id, "id");
    this.name = requireNonBlank(name);
  }

  private static String requireNonBlank(String value) {
    Objects.requireNonNull(value, "name");
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    return trimmed;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }
}
