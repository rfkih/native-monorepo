package id.co.nativeapp.finance.assets.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code deferral} aggregate (Phase 6, ADR 0020) — a prepaid expense or deferred revenue spread
 * straight-line over {@code months} starting at {@code start_period} (inclusive). Creating one
 * posts its opening entry in the same transaction ({@code Dr 1400 / Cr 1900} prepaid; {@code Dr
 * 1900 / Cr 2400} deferred revenue); the monthly amortization run then posts each month's share to
 * the P&amp;L.
 *
 * <p>Extends {@link Auditable}; covered by the {@code deferral} RLS policy (V34), scoped to {@code
 * app.current_tenant} (rules 4 + 5).
 */
@Entity
@Table(name = "deferral")
public class Deferral extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, updatable = false, length = 20)
  private DeferralKind kind;

  @Column(name = "description", nullable = false, length = 255)
  private String description;

  @Column(name = "total_minor", nullable = false, updatable = false)
  private long totalMinor;

  @Column(name = "months", nullable = false, updatable = false)
  private int months;

  /** The first amortization month ({@code YYYY-MM}), inclusive. */
  @Column(name = "start_period", nullable = false, updatable = false, length = 7)
  private String startPeriod;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  /** The opening GL entry raised at creation. */
  @Column(name = "creation_entry_id", nullable = false, updatable = false)
  private UUID creationEntryId;

  /** The client's Idempotency-Key (money posts on create — the fixed_asset rationale). */
  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
  private String idempotencyKey;

  protected Deferral() {
    // for JPA
  }

  /**
   * Creates a deferral. The caller has already posted the opening entry keyed on {@code
   * creationEntryId}.
   *
   * @param id the aggregate id (pre-allocated — also the opening entry's {@code source_event_id})
   * @param total the up-front amount (strictly positive)
   * @param months the straight-line spread (strictly positive)
   * @param startPeriod the first amortization month {@code YYYY-MM}
   * @throws IllegalArgumentException if a bound is violated
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static Deferral create(
      UUID id,
      DeferralKind kind,
      String description,
      Money total,
      int months,
      String startPeriod,
      UUID creationEntryId,
      String idempotencyKey) {
    Objects.requireNonNull(total, "total");
    if (!total.isPositive()) {
      throw new IllegalArgumentException("deferral total must be strictly positive: " + total);
    }
    if (months <= 0) {
      throw new IllegalArgumentException("deferral months must be strictly positive: " + months);
    }
    Deferral deferral = new Deferral();
    deferral.id = Objects.requireNonNull(id, "id");
    deferral.kind = Objects.requireNonNull(kind, "kind");
    deferral.description = requireDescription(description);
    deferral.totalMinor = total.amountMinor();
    deferral.months = months;
    deferral.startPeriod = Objects.requireNonNull(startPeriod, "startPeriod");
    deferral.currency = total.currency().getCurrencyCode();
    deferral.creationEntryId = Objects.requireNonNull(creationEntryId, "creationEntryId");
    deferral.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    return deferral;
  }

  private static String requireDescription(String description) {
    Objects.requireNonNull(description, "description");
    String trimmed = description.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("deferral description must not be blank");
    }
    return trimmed;
  }

  /** The full amount to spread — the schedule base. */
  public Money total() {
    return Money.ofMinor(totalMinor, getCurrency());
  }

  public UUID getId() {
    return id;
  }

  public DeferralKind getKind() {
    return kind;
  }

  public String getDescription() {
    return description;
  }

  public long getTotalMinor() {
    return totalMinor;
  }

  public int getMonths() {
    return months;
  }

  public String getStartPeriod() {
    return startPeriod;
  }

  /** The deferral currency (ISO-4217, stripped of any {@code CHAR} padding). */
  public String getCurrency() {
    return currency.strip();
  }

  public UUID getCreationEntryId() {
    return creationEntryId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }
}
