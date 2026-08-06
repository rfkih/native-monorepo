package id.co.nativeapp.restaurant.register.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One NON-CASH tender's reconciliation line for a closed register session (ADR 0038 phase 2) —
 * CARD/QRIS/ONLINE. Holds the server-computed {@code expected} (Σ sales − Σ refunds in the session
 * window), the cashier's {@code counted} figure, and the SIGNED {@code overShort = counted −
 * expected}. Cash is NOT here — it lives on {@link RegisterSession}. Extends {@link Auditable}
 * (rules 4 + 5), covered by the V26 RLS policy.
 */
@Entity
@Table(name = "register_session_tender")
public class RegisterSessionTender extends Auditable {

  @jakarta.persistence.Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "session_id", nullable = false, updatable = false)
  private UUID sessionId;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "tender_type", nullable = false, updatable = false)
  private String tenderType;

  @Column(name = "expected_minor", nullable = false, updatable = false)
  private long expectedMinor;

  @Column(name = "counted_minor", nullable = false, updatable = false)
  private long countedMinor;

  @Column(name = "over_short_minor", nullable = false, updatable = false)
  private long overShortMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  protected RegisterSessionTender() {
    // for JPA
  }

  private RegisterSessionTender(
      UUID sessionId,
      UUID businessId,
      String tenderType,
      long expectedMinor,
      long countedMinor,
      long overShortMinor,
      String currency) {
    this.id = UUID.randomUUID();
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.tenderType = Objects.requireNonNull(tenderType, "tenderType");
    this.expectedMinor = expectedMinor;
    this.countedMinor = countedMinor;
    this.overShortMinor = overShortMinor;
    this.currency = Objects.requireNonNull(currency, "currency");
  }

  /**
   * Records a non-cash tender's reconciliation at close.
   *
   * @param countedMinor the cashier's counted/settled figure (≥ 0; validated at the edge + DB
   *     CHECK)
   * @param overShortMinor SIGNED {@code counted − expected}
   */
  public static RegisterSessionTender of(
      UUID sessionId,
      UUID businessId,
      String tenderType,
      long expectedMinor,
      long countedMinor,
      long overShortMinor,
      String currency) {
    if (countedMinor < 0) {
      throw new IllegalArgumentException("countedMinor must be >= 0");
    }
    return new RegisterSessionTender(
        sessionId, businessId, tenderType, expectedMinor, countedMinor, overShortMinor, currency);
  }

  public UUID getId() {
    return id;
  }

  public UUID getSessionId() {
    return sessionId;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getTenderType() {
    return tenderType;
  }

  public long getExpectedMinor() {
    return expectedMinor;
  }

  public long getCountedMinor() {
    return countedMinor;
  }

  public long getOverShortMinor() {
    return overShortMinor;
  }

  /** ISO-4217 code; CHAR(3) padding stripped for callers. */
  public String getCurrency() {
    return currency == null ? null : currency.strip();
  }
}
