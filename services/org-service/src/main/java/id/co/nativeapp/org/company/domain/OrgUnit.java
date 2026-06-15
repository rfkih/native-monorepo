package id.co.nativeapp.org.company.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An {@code org_unit} — a node in a company's self-referencing org tree ({@code business_unit >
 * branch > outlet > team}). The first node created with a company (the "business") is a {@link
 * OrgUnitType#BUSINESS_UNIT} with a {@code null} parent; branches, outlets, and teams hang below
 * it, each under exactly one legal parent type ({@link OrgUnitType}).
 *
 * <p><strong>The aggregate owns the hierarchy invariant.</strong> Construction validates that the
 * node's {@code type} may legally sit under the supplied parent type ({@link
 * OrgUnitType#canBeChildOf}); a root type ({@code business_unit}) demands a {@code null} parent,
 * and every other type demands a parent of its single legal parent type. The same rule is
 * re-checked on a {@link #moveTo move}. The two things the aggregate cannot see in isolation — that
 * the parent is in the SAME company, and that a move would create a CYCLE — are enforced one layer
 * out, in the writer (the company constraint is also enforced a second time by RLS); the aggregate
 * guards a direct self-parent.
 *
 * <p><strong>Effective-dated.</strong> Each node carries {@code effective_from}/{@code
 * effective_to}; an open-ended row uses the far-future sentinel {@link #OPEN_ENDED} ({@code
 * 9999-12-31}), never {@code NULL} (ENGINEERING-STANDARDS §2.5). Deactivation closes the row by
 * stamping {@code effective_to} to "today" and flipping {@code active} to {@code false}.
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns and is
 * covered by the {@code org_unit} RLS policy in the Flyway migration (rule 4 + rule 5).
 */
@Entity
@Table(name = "org_unit")
public class OrgUnit extends Auditable {

  /** The far-future open-ended sentinel for {@code effective_to} (never {@code NULL}). */
  public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

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

  /** The legal employer this node belongs to (in the bootstrap, the company's single one). */
  @Column(name = "legal_employer_id", nullable = false, updatable = false)
  private UUID legalEmployerId;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  @Column(name = "effective_to", nullable = false)
  private LocalDate effectiveTo;

  protected OrgUnit() {
    // for JPA
  }

  /**
   * Creates an org unit with a freshly generated id, validating the hierarchy against the parent's
   * type.
   *
   * <p>The node is created active and open-ended (its {@code effective_to} is the {@link
   * #OPEN_ENDED} sentinel), effective from {@code effectiveFrom}.
   *
   * @param name the org-unit name; must be non-blank
   * @param type the org-unit kind; must be non-null
   * @param parentId the parent org unit, or {@code null} for a top-level node
   * @param parentType the parent's type, or {@code null} when {@code parentId} is {@code null};
   *     used to validate the {@code business_unit > branch > outlet > team} nesting
   * @param legalEmployerId the legal employer this node belongs to; must be non-null
   * @param effectiveFrom the date the node becomes effective; must be non-null
   * @throws IllegalArgumentException if the type may not sit under the parent type (mapped to 400)
   */
  public OrgUnit(
      String name,
      OrgUnitType type,
      UUID parentId,
      OrgUnitType parentType,
      UUID legalEmployerId,
      LocalDate effectiveFrom) {
    this.id = UUID.randomUUID();
    this.name = requireNonBlank(name, "name");
    this.type = Objects.requireNonNull(type, "type");
    this.legalEmployerId = Objects.requireNonNull(legalEmployerId, "legalEmployerId");
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = OPEN_ENDED;
    this.active = true;
    requireLegalPlacement(type, parentId, parentType);
    this.parentId = parentId;
  }

  /**
   * Validates that a node of {@code type} may sit under {@code parentId} (whose type is {@code
   * parentType}). A root type demands a {@code null} parent; every other type demands a parent of
   * its single legal parent type. Throws {@link IllegalArgumentException} (→ 400) otherwise.
   */
  private static void requireLegalPlacement(
      OrgUnitType type, UUID parentId, OrgUnitType parentType) {
    if (parentId == null) {
      if (!type.isRoot()) {
        throw new IllegalArgumentException(
            "A " + type + " must have a parent of type " + type.requiredParentType().orElseThrow());
      }
      return;
    }
    if (type.isRoot()) {
      throw new IllegalArgumentException(
          "A " + type + " is a top-level node and cannot have a parent");
    }
    Objects.requireNonNull(parentType, "parentType");
    if (!type.canBeChildOf(parentType)) {
      throw new IllegalArgumentException(
          "A "
              + type
              + " cannot be a child of a "
              + parentType
              + "; it must be a child of a "
              + type.requiredParentType().orElseThrow());
    }
  }

  /**
   * Renames this org unit. The new name must be non-blank.
   *
   * @return {@code true} if the name actually changed (so the caller can decide whether to emit a
   *     change event), {@code false} if it was identical
   */
  public boolean rename(String newName) {
    String trimmed = requireNonBlank(newName, "name");
    if (trimmed.equals(this.name)) {
      return false;
    }
    this.name = trimmed;
    return true;
  }

  /**
   * Moves this org unit under a new parent, re-validating the hierarchy. The node's own {@code
   * type} never changes; only its parent does, so the new parent must be of this type's single
   * legal parent type (or {@code null} for a root type). The aggregate guards a direct self-parent;
   * the writer guards a deeper cycle and the same-company constraint.
   *
   * @param newParentId the new parent, or {@code null} to move to the top level (root types only)
   * @param newParentType the new parent's type, or {@code null} when {@code newParentId} is null
   * @return {@code true} if the parent actually changed
   * @throws IllegalArgumentException if the placement is illegal, or the node is moved under itself
   */
  public boolean moveTo(UUID newParentId, OrgUnitType newParentType) {
    if (this.id.equals(newParentId)) {
      throw new IllegalArgumentException("An org unit cannot be its own parent");
    }
    requireLegalPlacement(this.type, newParentId, newParentType);
    if (Objects.equals(newParentId, this.parentId)) {
      return false;
    }
    this.parentId = newParentId;
    return true;
  }

  /**
   * Deactivates this org unit, closing its effective period: flips {@code active} to {@code false}
   * and stamps {@code effective_to} to {@code asOf} (the open-ended sentinel is replaced by the
   * close date). A no-op if already inactive.
   *
   * @param asOf the date the node ceases to be effective
   * @return {@code true} if the node was active and is now deactivated, {@code false} if it was
   *     already inactive
   */
  public boolean deactivate(LocalDate asOf) {
    Objects.requireNonNull(asOf, "asOf");
    if (!this.active) {
      return false;
    }
    this.active = false;
    this.effectiveTo = asOf;
    return true;
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

  public UUID getLegalEmployerId() {
    return legalEmployerId;
  }

  public boolean isActive() {
    return active;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }
}
