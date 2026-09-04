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
 * An {@code org_unit} — a physical selling location (an {@link OrgUnitType#OUTLET}) hanging
 * directly off the company. Since ADR 0070 the org "tree" is FLAT ({@code company > outlet}): the
 * division (business-unit) and team levels are gone, so there is no nesting, no parent to choose,
 * and nothing to move a node under. The company bootstrap seeds one outlet named after the company;
 * the owner adds and renames the rest on the Outlets page.
 *
 * <p><strong>The aggregate owns the flatness invariant.</strong> Construction always sets a {@code
 * null} parent — there is no legal parent for an outlet. The {@code parent_id} column and the
 * {@code parent_id} event field survive (always {@code null}) purely so ADR 0070 needed no schema
 * change and no consumer migration; see {@link OrgUnitType} for the same reasoning about {@code
 * type}.
 *
 * <p><strong>The vertical moved to the company (ADR 0070).</strong> It used to live on the
 * business-unit node and be inherited by outlets through a parent self-join; with that node gone it
 * is a company-level immutable ({@link Company#getVertical()}). An outlet carries no vertical of
 * its own, so the POS reads it once from the company rather than joining per outlet.
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

  /**
   * Always {@code null} since ADR 0070 (the tree is flat). The column is KEPT rather than dropped
   * so the event contract and the downstream read models needed no migration; nothing can set it.
   */
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
   * Creates an org unit with a freshly generated id. Since ADR 0070 every org unit is a top-level
   * {@link OrgUnitType#OUTLET}, so there is no hierarchy to validate — the parent is
   * unconditionally {@code null}.
   *
   * <p>The node is created active and open-ended (its {@code effective_to} is the {@link
   * #OPEN_ENDED} sentinel), effective from {@code effectiveFrom}.
   *
   * @param name the org-unit name; must be non-blank
   * @param type the org-unit kind; must be non-null (the only kind is {@link OrgUnitType#OUTLET})
   * @param legalEmployerId the legal employer this node belongs to; must be non-null
   * @param effectiveFrom the date the node becomes effective; must be non-null
   */
  public OrgUnit(String name, OrgUnitType type, UUID legalEmployerId, LocalDate effectiveFrom) {
    this.id = UUID.randomUUID();
    this.name = requireNonBlank(name, "name");
    this.type = Objects.requireNonNull(type, "type");
    this.legalEmployerId = Objects.requireNonNull(legalEmployerId, "legalEmployerId");
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = OPEN_ENDED;
    this.active = true;
    this.parentId = null;
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
   * Detaches this node from its parent, restoring the ADR 0070 flatness invariant on a row that
   * predates it. Sets {@code parent_id} to {@code null}; a no-op on a node that is already
   * top-level.
   *
   * <p><strong>The one-shot migration path only.</strong> This exists for {@code
   * OrgTreeFlatteningReconciler}, which lifts a company's outlets out from under their retired
   * business unit. Nothing in the normal request path calls it — a newly created outlet is already
   * parentless, and there is no "move" operation any more. It lives on the aggregate rather than as
   * a raw {@code UPDATE} so the invariant stays owned here, and so the caller can emit {@code
   * OrgUnitChanged}/{@code MOVED} from the node's real post-change state.
   *
   * @return {@code true} if the node actually had a parent and is now top-level
   */
  public boolean detachFromParent() {
    if (this.parentId == null) {
      return false;
    }
    this.parentId = null;
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

  /**
   * Reactivates this org unit, REOPENING its effective period: flips {@code active} back to {@code
   * true} and restores {@code effective_to} to the {@link #OPEN_ENDED} sentinel — the exact inverse
   * of {@link #deactivate}. A no-op if already active.
   *
   * <p>Since ADR 0070 the tree is flat, so there are no ancestors or descendants: reactivation
   * concerns exactly this node and can never leave an active node under an inactive parent (the
   * invariant the old nested tree needed a writer-side guard for).
   *
   * @return {@code true} if the node was inactive and is now reactivated, {@code false} if it was
   *     already active
   */
  public boolean reactivate() {
    if (this.active) {
      return false;
    }
    this.active = true;
    this.effectiveTo = OPEN_ENDED;
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

  /** Always {@code null} since ADR 0070 — kept for the event/read-model shape only. */
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
