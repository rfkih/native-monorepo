package id.co.nativeapp.org.user.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A console-user ↔ outlet assignment: records that the Keycloak user {@code userId} may work at the
 * org-unit {@code orgUnitId} (which must be an active {@code OUTLET}) within a company.
 *
 * <p>This is NOT the HR employee assignment (employee-service owns that, with contract/payroll
 * data). {@code userId} is the Keycloak subject id — stable, non-PII, safe to store here without
 * column-level encryption (rule 6 does not apply; no salary, NIK, or bank-account data lives here).
 *
 * <p><strong>Effective-dating.</strong> Open assignments carry the far-future sentinel {@link
 * #OPEN_ENDED} ({@code 9999-12-31}) in {@code effectiveTo}; closing an assignment stamps {@code
 * effectiveTo} to today and flips {@code active} to {@code false}. The {@code active} flag is a
 * fast filter for the hot "my outlets" read without requiring a date-range predicate on every
 * query.
 *
 * <p><strong>Uniqueness invariant.</strong> At most one row per {@code (company_id, user_id,
 * org_unit_id)} exists in the table. The DB carries a UNIQUE constraint on this triple (V5
 * migration). To re-assign a previously removed outlet the existing (inactive) row is reopened
 * ({@link #reopen()}) rather than duplicated.
 *
 * <p>Extends {@link Auditable} so all audit columns ({@code created_at/by}, {@code updated_at/by},
 * {@code version}, {@code company_id}) are inherited and the row is covered by the {@code
 * user_outlet_assignment} RLS policy (rules 4 + 5).
 */
@Entity
@Table(name = "user_outlet_assignment")
public class UserOutletAssignment extends Auditable {

  /** Far-future open-ended sentinel for {@code effective_to} — never {@code NULL}. */
  public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** The Keycloak subject id of the console user. VARCHAR(64) — same type as audit columns. */
  @Column(name = "user_id", nullable = false, updatable = false, length = 64)
  private String userId;

  /**
   * The org_unit that must be an active OUTLET. No FK constraint — the aggregate enforces the
   * type=OUTLET + active=true guard before persisting (same pattern as org_unit.parent_id in V1).
   */
  @Column(name = "org_unit_id", nullable = false, updatable = false)
  private UUID orgUnitId;

  @Column(name = "effective_from", nullable = false, updatable = false)
  private LocalDate effectiveFrom;

  @Column(name = "effective_to", nullable = false)
  private LocalDate effectiveTo;

  /** Fast filter for the hot "my outlets" query without a date-range predicate. */
  @Column(name = "active", nullable = false)
  private boolean active;

  protected UserOutletAssignment() {
    // for JPA
  }

  /**
   * Creates a new, active, open-ended assignment. The caller is responsible for validating that
   * {@code orgUnitId} resolves to an active OUTLET before calling this constructor.
   *
   * @param userId the Keycloak subject id of the console user; must be non-blank
   * @param orgUnitId the outlet org-unit UUID; must be non-null
   * @param effectiveFrom the date the assignment becomes effective; must be non-null
   */
  public UserOutletAssignment(String userId, UUID orgUnitId, LocalDate effectiveFrom) {
    this.id = UUID.randomUUID();
    this.userId = requireNonBlank(userId, "userId");
    this.orgUnitId = Objects.requireNonNull(orgUnitId, "orgUnitId");
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = OPEN_ENDED;
    this.active = true;
  }

  /**
   * Closes this assignment: stamps {@code effectiveTo} to {@code asOf} and flips {@code active} to
   * {@code false}. A no-op if already inactive.
   *
   * @param asOf the date the assignment ceases to be effective
   * @return {@code true} if the assignment was active and is now closed, {@code false} if it was
   *     already inactive
   */
  public boolean close(LocalDate asOf) {
    Objects.requireNonNull(asOf, "asOf");
    if (!this.active) {
      return false;
    }
    this.active = false;
    this.effectiveTo = asOf;
    return true;
  }

  /**
   * Reopens a previously closed assignment: flips {@code active} back to {@code true} and restores
   * {@code effectiveTo} to the {@link #OPEN_ENDED} sentinel. A no-op if already active.
   *
   * @return {@code true} if the assignment was inactive and is now reopened, {@code false} if it
   *     was already active
   */
  public boolean reopen() {
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

  public String getUserId() {
    return userId;
  }

  public UUID getOrgUnitId() {
    return orgUnitId;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  public boolean isActive() {
    return active;
  }
}
