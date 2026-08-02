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
 */
public interface WorkCalendarRepository extends JpaRepository<WorkCalendar, UUID> {

  @Query(value = "SELECT * FROM work_calendar LIMIT 1", nativeQuery = true)
  Optional<WorkCalendar> findOne();
}
