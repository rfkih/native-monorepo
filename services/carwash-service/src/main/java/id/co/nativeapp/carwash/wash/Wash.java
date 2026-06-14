package id.co.nativeapp.carwash.wash;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code wash} aggregate — carwash-service's system of record for a recorded wash, and the
 * source of the {@code SaleRecorded} and {@code MetricPublished} events.
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns ({@code
 * created_at}/{@code created_by}, {@code updated_at}/{@code updated_by}, {@code version}, {@code
 * company_id}) and is covered by the {@code wash} RLS policy in the Flyway baseline (rule 4 + rule
 * 5).
 *
 * <p>The monetary amount is a {@code libs/money} {@link Money} (rule 8 — integer minor units +
 * ISO-4217 currency, never a float), persisted via {@link MoneyEmbeddable} as {@code amount_minor
 * BIGINT} + {@code currency CHAR(3)}. This is the wash TOTAL (the wash plus any upsell) — the
 * amount carried on {@code SaleRecorded} so finance consolidates it. An optional upsell carries its
 * own minor-units amount (in the same wash currency — one transaction currency per wash) and feeds
 * the {@code upsell_amount} metric.
 *
 * <p>{@code businessId} is the carwash OUTLET (an org_unit). {@code idempotency_key} is the
 * client's request id; together with {@code company_id} it carries a {@code UNIQUE} constraint so a
 * retried record-wash resolves to the same row (producer idempotency — exactly one {@code
 * SaleRecorded} on retry).
 */
@Entity
@Table(name = "wash")
public class Wash extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "bay", nullable = false, updatable = false)
  private String bay;

  @Column(name = "upsell_name", updatable = false)
  private String upsellName;

  @Column(name = "upsell_amount_minor", updatable = false)
  private Long upsellAmountMinor;

  @Embedded private MoneyEmbeddable amount;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "idempotency_key", nullable = false, updatable = false)
  private String idempotencyKey;

  protected Wash() {
    // for JPA
  }

  /**
   * Creates a new wash with a freshly generated id.
   *
   * @param businessId the carwash outlet (org_unit) the wash was recorded at
   * @param bay the wash bay it ran on
   * @param upsell the optional upsell (name + amount); {@link Optional#empty()} for no upsell. The
   *     upsell amount MUST share the wash {@code amount}'s currency (one transaction currency per
   *     wash).
   * @param amount the wash total as {@link Money} (never a float); includes any upsell amount
   * @param occurredAt when the wash occurred
   * @param idempotencyKey the client's request id (dedupe key with company_id)
   */
  public Wash(
      UUID businessId,
      String bay,
      Optional<Upsell> upsell,
      Money amount,
      Instant occurredAt,
      String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.bay = requireNonBlank(bay, "bay");
    this.amount = MoneyEmbeddable.of(Objects.requireNonNull(amount, "amount"));
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    this.idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(upsell, "upsell")
        .ifPresent(
            u -> {
              if (!u.amount().currency().equals(amount.currency())) {
                throw new IllegalArgumentException(
                    "upsell currency must match the wash currency (one transaction currency per"
                        + " wash)");
              }
              this.upsellName = requireNonBlank(u.name(), "upsell name");
              this.upsellAmountMinor = u.amount().amountMinor();
            });
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getBay() {
    return bay;
  }

  /** Whether this wash carried an upsell. */
  public boolean hasUpsell() {
    return upsellName != null;
  }

  public String getUpsellName() {
    return upsellName;
  }

  /**
   * The upsell amount in the wash currency's minor units, or {@code 0} when there was no upsell. A
   * primitive long (not a float) — the {@code upsell_amount} metric value (rule 8).
   */
  public long getUpsellAmountMinor() {
    return upsellAmountMinor == null ? 0L : upsellAmountMinor;
  }

  /** The wash total as a {@link Money} value (reconstructed from its columns). */
  public Money getAmount() {
    return amount.toMoney();
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  /**
   * An optional upsell on a wash — a name plus a {@link Money} amount (in the wash's currency). A
   * value record the aggregate validates; never a float (rule 8).
   *
   * @param name the upsell name (e.g. {@code "wax"})
   * @param amount the upsell amount as {@link Money}
   */
  public record Upsell(String name, Money amount) {
    public Upsell {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(amount, "amount");
    }
  }
}
