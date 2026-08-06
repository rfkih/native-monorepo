package id.co.nativeapp.employee.timeoff.repository;

import id.co.nativeapp.employee.timeoff.domain.WorkCalendar;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for the {@link WorkCalendar} row — exactly ONE per tenant. No manual
 * {@code WHERE company_id} — RLS scopes the lookup (rule 5), so there is nothing further to key the
 * single-row read/write by. Returns the full entity (not a projection): a single-row
 * tenant-settings resource loaded whole for both display and mutation, the same shape as any {@code
 * findById} single- resource GET elsewhere in this codebase (CODE-STRUCTURE §3.3).
 *
 * <p>The full-entity load names every mapped column explicitly rather than {@code SELECT *} — the
 * project forbids {@code SELECT *} anywhere (enforced by {@code scripts/check-no-select-star.sh}):
 * an explicit list is stable against a future column addition binding to the wrong entity field.
 */
public interface WorkCalendarRepository extends JpaRepository<WorkCalendar, UUID> {

  @Query(
      value =
          "SELECT id, days_per_week, monthly_divisor, created_at, created_by, updated_at,"
              + " updated_by, version, company_id FROM work_calendar LIMIT 1",
      nativeQuery = true)
  Optional<WorkCalendar> findOne();
}
