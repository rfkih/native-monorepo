package id.co.nativeapp.employee.timeoff.service;

import id.co.nativeapp.employee.timeoff.domain.WorkCalendar;
import id.co.nativeapp.employee.timeoff.repository.WorkCalendarRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for the tenant's single {@link WorkCalendar} row
 * (ADR 0033 §6). A distinct bean so {@code @Transactional} + {@link RlsAutoApplyAspect} engage
 * (rule 5). Flyway cannot seed this row (RLS forbids an unscoped INSERT against a FORCE-RLS table —
 * the {@code expense_category} default-set precedent, ADR 0030), so {@link #seedDefaultIfMissing}
 * lazily creates the {@code (5, 21)} default the first time anything reads it.
 */
@Component
public class WorkCalendarWriter {

  private final WorkCalendarRepository calendarRepository;

  public WorkCalendarWriter(WorkCalendarRepository calendarRepository) {
    this.calendarRepository = calendarRepository;
  }

  /**
   * Returns the tenant's row, creating the {@code (5, 21)} default first if none exists yet. A
   * concurrent double-seed race (two callers both observing "no row yet") is caught by the {@code
   * uq_work_calendar_company} UNIQUE index — the loser's insert fails and this re-reads the
   * winner's row instead of raising (the {@code ExpenseCategoryWriter} duplicate-name idiom).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public WorkCalendar seedDefaultIfMissing() {
    return calendarRepository
        .findOne()
        .orElseGet(
            () -> {
              String tenant = TenantContext.require().companyId();
              WorkCalendar calendar =
                  new WorkCalendar(
                      WorkCalendar.DEFAULT_DAYS_PER_WEEK, WorkCalendar.DEFAULT_MONTHLY_DIVISOR);
              calendar.setCompanyId(tenant);
              try {
                return calendarRepository.saveAndFlush(calendar);
              } catch (DataIntegrityViolationException raced) {
                return calendarRepository.findOne().orElseThrow(() -> raced);
              }
            });
  }

  /**
   * Upserts the tenant's row (create-or-replace, PUT semantics): creates it if absent, else
   * replaces both values on the existing row.
   *
   * @throws IllegalArgumentException if either value is outside its allowed set (→ 400)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public WorkCalendar upsert(int daysPerWeek, int monthlyDivisor) {
    java.util.Optional<WorkCalendar> existing = calendarRepository.findOne();
    if (existing.isPresent()) {
      WorkCalendar calendar = existing.get();
      calendar.update(daysPerWeek, monthlyDivisor);
      return calendarRepository.save(calendar);
    }
    String tenant = TenantContext.require().companyId();
    WorkCalendar calendar = new WorkCalendar(daysPerWeek, monthlyDivisor);
    calendar.setCompanyId(tenant);
    try {
      return calendarRepository.saveAndFlush(calendar);
    } catch (DataIntegrityViolationException raced) {
      // A concurrent first-PUT/seed won the race; apply THIS caller's values onto the winner's
      // row rather than silently discarding the request.
      WorkCalendar winner = calendarRepository.findOne().orElseThrow(() -> raced);
      winner.update(daysPerWeek, monthlyDivisor);
      return calendarRepository.save(winner);
    }
  }
}
