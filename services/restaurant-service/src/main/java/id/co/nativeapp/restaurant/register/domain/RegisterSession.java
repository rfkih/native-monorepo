package id.co.nativeapp.restaurant.register.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code cash_register_session} aggregate (ADR 0036, closing kasir) — one cash drawer at one
 * outlet, OPENed with a counted float and CLOSEd with a physical recount. At most one OPEN session
 * per outlet at a time ({@code uq_crs_one_open_per_outlet} partial unique is the invariant; the
 * service-layer check is best-effort).
 *
 * <p>The close figures are SERVER-computed (never client-supplied, except {@code
 * countedCashMinor} which IS the client's physical count): {@code expectedCash = openingFloat +
 * cashSales − cashRefunds}; {@code overShort = counted − expected} (signed: negative = short,
 * positive = over). All money is integer minor units in one {@code currency} (rule 8).
 *
 * <p>Extends {@link Auditable} — mandatory audit + tenancy columns, covered by the V21 RLS policy
 * (rules 4 + 5).
 */
@Entity
@Table(name = "cash_register_session")
public class RegisterSession extends Auditable {

  /** Session lifecycle: OPEN (trading) → CLOSED (counted, variance emitted). One-way. */
  public static final String STATUS_OPEN = "OPEN";

  public static final String STATUS_CLOSED = "CLOSED";

  @jakarta.persistence.Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "business_date", nullable = false, updatable = false)
  private LocalDate businessDate;

  @Column(name = "opened_at", nullable = false, updatable = false)
  private Instant openedAt;

  @Column(name = "opening_float_minor", nullable = false, updatable = false)
  private long openingFloatMinor;

  @Column(name = "currency", nullable = false, updatable = false)
  private String currency;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "cash_sales_minor")
  private Long cashSalesMinor;

  @Column(name = "cash_refunds_minor")
  private Long cashRefundsMinor;

  @Column(name = "expected_cash_minor")
  private Long expectedCashMinor;

  @Column(name = "counted_cash_minor")
  private Long countedCashMinor;

  @Column(name = "over_short_minor")
  private Long overShortMinor;

  @Column(name = "open_idempotency_key", nullable = false, updatable = false)
  private String openIdempotencyKey;

  @Column(name = "close_idempotency_key")
  private String closeIdempotencyKey;

  protected RegisterSession() {
    // for JPA
  }

  private RegisterSession(
      UUID businessId,
      LocalDate businessDate,
      Instant openedAt,
      long openingFloatMinor,
      String currency,
      String openIdempotencyKey) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.status = STATUS_OPEN;
    this.businessDate = Objects.requireNonNull(businessDate, "businessDate");
    this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
    this.openingFloatMinor = openingFloatMinor;
    this.currency = Objects.requireNonNull(currency, "currency");
    this.openIdempotencyKey = Objects.requireNonNull(openIdempotencyKey, "openIdempotencyKey");
  }

  /**
   * Opens a new drawer session.
   *
   * @param openingFloatMinor the counted change fund (≥ 0; validated by the writer + DB CHECK)
   */
  public static RegisterSession open(
      UUID businessId,
      LocalDate businessDate,
      Instant openedAt,
      long openingFloatMinor,
      String currency,
      String openIdempotencyKey) {
    if (openingFloatMinor < 0) {
      throw new IllegalArgumentException("openingFloatMinor must be >= 0");
    }
    return new RegisterSession(
        businessId, businessDate, openedAt, openingFloatMinor, currency, openIdempotencyKey);
  }

  /**
   * Closes the session with the server-computed figures. One-way OPEN → CLOSED; the writer holds a
   * pessimistic row lock while computing, so the status guard here is the in-JVM backstop.
   *
   * @throws RegisterSessionNotOpenException if the session is not OPEN
   */
  public void close(
      Instant closedAt,
      long cashSalesMinor,
      long cashRefundsMinor,
      long expectedCashMinor,
      long countedCashMinor,
      long overShortMinor,
      String closeIdempotencyKey) {
    if (!STATUS_OPEN.equals(status)) {
      throw new RegisterSessionNotOpenException(id, status);
    }
    if (countedCashMinor < 0) {
      throw new IllegalArgumentException("countedCashMinor must be >= 0");
    }
    this.status = STATUS_CLOSED;
    this.closedAt = Objects.requireNonNull(closedAt, "closedAt");
    this.cashSalesMinor = cashSalesMinor;
    this.cashRefundsMinor = cashRefundsMinor;
    this.expectedCashMinor = expectedCashMinor;
    this.countedCashMinor = countedCashMinor;
    this.overShortMinor = overShortMinor;
    this.closeIdempotencyKey = Objects.requireNonNull(closeIdempotencyKey, "closeIdempotencyKey");
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getStatus() {
    return status;
  }

  public LocalDate getBusinessDate() {
    return businessDate;
  }

  public Instant getOpenedAt() {
    return openedAt;
  }

  public long getOpeningFloatMinor() {
    return openingFloatMinor;
  }

  /** ISO-4217 code; CHAR(3) padding is stripped for callers. */
  public String getCurrency() {
    return currency == null ? null : currency.strip();
  }

  public Instant getClosedAt() {
    return closedAt;
  }

  public Long getCashSalesMinor() {
    return cashSalesMinor;
  }

  public Long getCashRefundsMinor() {
    return cashRefundsMinor;
  }

  public Long getExpectedCashMinor() {
    return expectedCashMinor;
  }

  public Long getCountedCashMinor() {
    return countedCashMinor;
  }

  public Long getOverShortMinor() {
    return overShortMinor;
  }

  public String getOpenIdempotencyKey() {
    return openIdempotencyKey;
  }

  public String getCloseIdempotencyKey() {
    return closeIdempotencyKey;
  }
}
