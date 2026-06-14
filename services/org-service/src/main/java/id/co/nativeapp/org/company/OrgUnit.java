package id.co.nativeapp.org.company;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * An {@code org_unit} — a node in a company's self-referencing org tree (business_unit | branch |
 * outlet | team). The first node created with a company is the "business" (a {@link
 * OrgUnitType#BUSINESS_UNIT} with a {@code null} parent); further nodes hang below it.
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns ({@code
 * created_at}/{@code created_by}, {@code updated_at}/{@code updated_by}, {@code version}, {@code
 * company_id}) and is covered by the {@code org_unit} RLS policy in the Flyway baseline (rule 4 +
 * rule 5). It validates its own invariants on construction (non-blank name, non-null type) — the
 * aggregate owns its invariants (CLAUDE.md).
 */
@Entity
@Table(name = "org_unit")
public class OrgUnit extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, updatable = false, length = 32)
  private OrgUnitType type;

  @Column(name = "parent_id")
  private UUID parentId;

  protected OrgUnit() {
    // for JPA
  }

  /**
   * Creates an org unit with a freshly generated id under the given parent (or {@code null} for a
   * top-level node such as the first business).
   *
   * @param name the org-unit name; must be non-blank
   * @param type the org-unit kind; must be non-null
   * @param parentId the parent org unit, or {@code null} for a top-level node
   */
  public OrgUnit(String name, OrgUnitType type, UUID parentId) {
    this.id = UUID.randomUUID();
    this.name = requireNonBlank(name, "name");
    this.type = Objects.requireNonNull(type, "type");
    this.parentId = parentId;
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public OrgUnitType getType() {
    return type;
  }

  public UUID getParentId() {
    return parentId;
  }
}
