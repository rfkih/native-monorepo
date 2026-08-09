package id.co.nativeapp.employee.operator.repository;

import id.co.nativeapp.employee.operator.domain.OperatorPin;
import id.co.nativeapp.employee.operator.projection.OperatorRosterView;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link OperatorPin} aggregate.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC on every {@code @Transactional} method (rule 5). A lookup
 * that resolves to another tenant's operator PIN is invisible under RLS (returns empty), so a
 * cross-tenant read fails closed without any hand-written predicate.
 */
public interface OperatorPinRepository extends JpaRepository<OperatorPin, UUID> {

  /** The operator PIN row for an employee (within the bound tenant), if one has been set. */
  Optional<OperatorPin> findByEmployeeId(UUID employeeId);

  /**
   * The PIN-sign-in-capable roster of an outlet (ADR 0049 P3b, {@code GET
   * /api/v1/operators/roster}): every employee who is BOTH (a) actively assigned to {@code
   * businessId} as of {@code asOf} (the fleet's standard effective-dated predicate — mirrors {@link
   * id.co.nativeapp.employee.assignment.repository.AssignmentRepository#existsActiveAssignment})
   * AND (b) carries an {@code operator_pin} row (so they can actually sign in). The inner {@code
   * JOIN} to {@code operator_pin} enforces (b); the {@code EXISTS} against {@code assignment}
   * enforces (a) without duplicating a row for an employee who happens to hold more than one
   * concurrent assignment to the same outlet. Each of the three tables enforces its OWN RLS policy
   * — no manual {@code WHERE company_id} needed (rule 5). Sorted by name for the till's PIN-picker
   * list.
   *
   * <p><strong>This endpoint deliberately enumerates who can sign in (name only) — see {@code
   * operator.controller.OperatorRosterController} for why that is intentional, unlike {@code POST
   * /api/v1/operators/session}'s non-enumeration hardening.</strong>
   */
  @Query(
      value =
          """
          SELECT e.id        AS employee_id,
                 e.full_name AS full_name
            FROM employee e
            JOIN operator_pin p ON p.employee_id = e.id
           WHERE EXISTS (
                   SELECT 1
                     FROM assignment a
                    WHERE a.employee_id = e.id
                      AND a.org_unit_id = :businessId
                      AND a.effective_from <= :asOf
                      AND a.effective_to >= :asOf
                 )
           ORDER BY e.full_name
          """,
      nativeQuery = true)
  List<OperatorRosterView> findRoster(
      @Param("businessId") UUID businessId, @Param("asOf") LocalDate asOf);

  /**
   * The sign-in-capable roster of an outlet whose {@code outlet_operator_policy} is {@code
   * require_pin = false} (the per-outlet no-PIN toggle, ADR 0049): every employee who is BOTH (a)
   * actively assigned to {@code businessId} as of {@code asOf} AND (b) has a non-blank {@code
   * user_id} (login-linked — the source of the minted token's {@code operatorUserId}, so an
   * unlinked employee could never be picked here even without a PIN gate). Unlike {@link
   * #findRoster}, this does NOT require an {@code operator_pin} row — a no-PIN outlet has no PIN
   * concept for its roster to filter on. Each of the two tables enforces its OWN RLS policy — no
   * manual {@code WHERE company_id} needed (rule 5). Sorted by name for the till's picker list;
   * same deliberate enumeration (name only, rule 6) as {@link #findRoster}.
   */
  @Query(
      value =
          """
          SELECT e.id        AS employee_id,
                 e.full_name AS full_name
            FROM employee e
           WHERE e.user_id IS NOT NULL
             AND e.user_id <> ''
             AND EXISTS (
                   SELECT 1
                     FROM assignment a
                    WHERE a.employee_id = e.id
                      AND a.org_unit_id = :businessId
                      AND a.effective_from <= :asOf
                      AND a.effective_to >= :asOf
                 )
           ORDER BY e.full_name
          """,
      nativeQuery = true)
  List<OperatorRosterView> findLinkedRoster(
      @Param("businessId") UUID businessId, @Param("asOf") LocalDate asOf);
}
