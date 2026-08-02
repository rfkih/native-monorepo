package id.co.nativeapp.employee.timeoff.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code work_calendar} row — ONE per tenant (ADR 0033 §6): {@code daysPerWeek} (5 or 6) and
 * {@code monthlyDivisor} (21 or 25, the PP 36/2021 daily-wage convention Track P Phase P7 divides
 * an UNPAID-leave day's pay by). Seeded lazily on first read with the default {@code (5, 21)}
 * rather than by a Flyway seed row — RLS forbids a Flyway {@code INSERT} against a FORCE-RLS table
 * with no session GUC bound (the {@code expense_category} default-set precedent, ADR 0030); {@code
 * WorkCalendarWriter#upsert} is the only writer.
 *
 * <p>Extends {@link Auditable} (rule 4); under the {@code work_calendar} RLS policy (rule 5, V12),
 * unique per {@code company_id}.
 */
@Entity
@Table(name = "work_calendar")
public class WorkCalendar extends Auditable {

  /**
   * The allowed {@code days_per_week} values (DB {@code CHECK}, mirrored here for a 400 not 500).
   */
  public static final Set<Integer> ALLOWED_DAYS_PER_WEEK = Set.of(5, 6);

  /**
   * The allowed {@code monthly_divisor} values — PP 36/2021 (21 for a 5-day week, 25 for a 6-day).
   */
  public static final Set<Integer> ALLOWED_MONTHLY_DIVISOR = Set.of(21, 25);

  /** The default row a tenant is seeded with on first read: a 5-day week, divisor 21. */
  public static final int DEFAULT_DAYS_PER_WEEK = 5;

  /** See {@link #DEFAULT_DAYS_PER_WEEK}. */
  public static final int DEFAULT_MONTHLY_DIVISOR = 21;

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "days_per_week", nullable = false)
  private int daysPerWeek;

  @Column(name = "monthly_divisor", nullable = false)
  private int monthlyDivisor;

  protected WorkCalendar() {
    // for JPA
  }

  /**
   * Creates a work-calendar row.
   *
   * @throws IllegalArgumentException if either value is outside its allowed set (→ 400)
   */
  public WorkCalendar(int daysPerWeek, int monthlyDivisor) {
    this.id = UUID.randomUUID();
    validate(daysPerWeek, monthlyDivisor);
    this.daysPerWeek = daysPerWeek;
    this.monthlyDivisor = monthlyDivisor;
  }

  /**
   * Replaces both values (the PUT upsert idiom).
   *
   * @throws IllegalArgumentException if either value is outside its allowed set (→ 400)
   */
  public void update(int daysPerWeek, int monthlyDivisor) {
    validate(daysPerWeek, monthlyDivisor);
    this.daysPerWeek = daysPerWeek;
    this.monthlyDivisor = monthlyDivisor;
  }

  private static void validate(int daysPerWeek, int monthlyDivisor) {
    if (!ALLOWED_DAYS_PER_WEEK.contains(daysPerWeek)) {
      throw new IllegalArgumentException("daysPerWeek must be 5 or 6: " + daysPerWeek);
    }
    if (!ALLOWED_MONTHLY_DIVISOR.contains(monthlyDivisor)) {
      throw new IllegalArgumentException("monthlyDivisor must be 21 or 25: " + monthlyDivisor);
    }
  }

  public UUID getId() {
    return id;
  }

  public int getDaysPerWeek() {
    return daysPerWeek;
  }

  public int getMonthlyDivisor() {
    return monthlyDivisor;
  }

  @Override
  public String toString() {
    return "WorkCalendar[daysPerWeek=" + daysPerWeek + ", monthlyDivisor=" + monthlyDivisor + "]";
  }
}
