package id.co.nativeapp.employee.expense.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code expense_category} aggregate — the admin catalog a claim is raised against. {@code
 * gl_hint} is the ONLY link to finance's posting: on approval, finance resolves it to a
 * chart-of-account expense account via the effective {@code mapping_rule} (suspense fail-safe on an
 * unrecognised hint) — {@code taxable} is stored but drives no posting yet (ADR 0030 §9, the
 * natura/per-diem wiring lands with the payroll program's TAXABLE_ALLOWANCE work).
 *
 * <p>Extends {@link Auditable} (rule 4); under the {@code expense_category} RLS policy (rule 5, V9)
 * with a per-tenant case-insensitive unique name.
 */
@Entity
@Table(name = "expense_category")
public class ExpenseCategory extends Auditable {

  /**
   * The ONLY {@code gl_hint} values a category may carry (ADR 0030): {@code ""} (general), {@code
   * cogs}, {@code supplies}, {@code utilities}. A new hint requires a finance {@code mapping_rule}
   * seed first (ask integration-engineer / tech-lead) — constraining creation/update here means an
   * unrecognised hint can never silently reach an approved claim's event.
   */
  public static final Set<String> GL_HINT_WHITELIST = Set.of("", "cogs", "supplies", "utilities");

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 128)
  private String name;

  @Column(name = "gl_hint", nullable = false, length = 64)
  private String glHint;

  @Column(name = "taxable", nullable = false)
  private boolean taxable;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected ExpenseCategory() {
    // for JPA
  }

  /**
   * Creates an active expense category with a freshly generated id.
   *
   * @param name the category's display name; must be non-blank
   * @param glHint the finance posting hint; must be in {@link #GL_HINT_WHITELIST} ({@code null}
   *     normalizes to {@code ""})
   * @param taxable whether the category is (informationally, v1) taxable
   * @throws InvalidGlHintException if {@code glHint} is not whitelisted (→ 422)
   */
  public ExpenseCategory(String name, String glHint, boolean taxable) {
    this.id = UUID.randomUUID();
    this.name = requireNonBlank(name, "name");
    this.glHint = validateGlHint(glHint);
    this.taxable = taxable;
    this.active = true;
  }

  /**
   * Applies a partial update. A {@code null} argument leaves the corresponding field unchanged;
   * {@code active} is a tri-state {@link Boolean} for the same reason.
   *
   * @throws InvalidGlHintException if a non-null {@code newGlHint} is not whitelisted (→ 422)
   */
  public void update(String newName, String newGlHint, Boolean newTaxable, Boolean newActive) {
    if (newName != null) {
      this.name = requireNonBlank(newName, "name");
    }
    if (newGlHint != null) {
      this.glHint = validateGlHint(newGlHint);
    }
    if (newTaxable != null) {
      this.taxable = newTaxable;
    }
    if (newActive != null) {
      this.active = newActive;
    }
  }

  /** Validates a {@code gl_hint} against {@link #GL_HINT_WHITELIST}, normalizing {@code null}. */
  public static String validateGlHint(String glHint) {
    String normalized = glHint == null ? "" : glHint.strip();
    if (!GL_HINT_WHITELIST.contains(normalized)) {
      throw new InvalidGlHintException(normalized);
    }
    return normalized;
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

  public String getGlHint() {
    return glHint;
  }

  public boolean isTaxable() {
    return taxable;
  }

  public boolean isActive() {
    return active;
  }

  @Override
  public String toString() {
    return "ExpenseCategory[id=" + id + ", name=" + name + ", active=" + active + "]";
  }
}
