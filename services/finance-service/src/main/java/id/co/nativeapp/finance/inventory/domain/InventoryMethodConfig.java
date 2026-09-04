package id.co.nativeapp.finance.inventory.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The ONCE-ONLY, per-company {@code inventory_method_config} election row (ADR 0067 §5, table V53;
 * activation columns V57) — the finance-owned "is this company perpetual-inventory-active, and as
 * of which cutover period" decision every Phase B/C consumer ({@link
 * id.co.nativeapp.finance.inventory.service.PerpetualInventoryReader}) reads.
 *
 * <p><strong>Written ONLY by {@link
 * id.co.nativeapp.finance.inventory.service.InventoryMethodActivationWriter}</strong> — an OWNER,
 * and only an owner (gateway-enforced), deliberately activates an existing company; there is NO
 * auto-activation anywhere in the system (a fresh company stays inactive exactly like every
 * existing one until this row is explicitly inserted). {@code UNIQUE(company_id)} (V53) makes this
 * genuinely once-only — there is no deactivate/amend flow.
 *
 * <p>{@code idempotencyKey} + {@code openingInventoryValueMinor} + {@code currency} + {@code
 * cutoverPeriod} together are the activation payload the writer's replay probe compares: a retry
 * under the SAME key with the SAME payload replays (200, no second posting); anything else against
 * an already-active company is {@code 409} (activation is once-only, ADR 0037's opening- balance
 * idiom). {@code openingEntryId} is the posted {@code Dr 1100 / Cr 3900} journal entry, or {@code
 * null} when {@code openingInventoryValueMinor} was zero (no journal booked, still activated).
 *
 * <p>Extends {@link Auditable} (rule 4) and is under {@code FORCE ROW LEVEL SECURITY} (rule 5,
 * V53).
 */
@Entity
@Table(name = "inventory_method_config")
public class InventoryMethodConfig extends Auditable {

  /** The only method this table supports today (ADR 0067 explicitly rules out FIFO/lot for now). */
  public static final String METHOD_PERPETUAL = "PERPETUAL";

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "method", nullable = false, updatable = false, length = 16)
  private String method;

  @Column(name = "perpetual_active", nullable = false)
  private boolean perpetualActive;

  @Column(name = "cutover_period", nullable = false, updatable = false, length = 7)
  private String cutoverPeriod;

  @Column(name = "activated_at", nullable = false, updatable = false)
  private Instant activatedAt;

  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
  private String idempotencyKey;

  @Column(name = "opening_inventory_value_minor", nullable = false, updatable = false)
  private long openingInventoryValueMinor;

  @Column(name = "opening_entry_id", nullable = true, updatable = false)
  private UUID openingEntryId;

  protected InventoryMethodConfig() {
    // for JPA
  }

  /**
   * Builds a fresh activation row — the ONLY way to construct one (owner-triggered, once per
   * company).
   *
   * @param cutoverPeriod {@code YYYY-MM}; already validated by the caller
   * @param activatedAt the moment of activation (server clock, not the cutover date)
   * @param currency ISO-4217 code the activation payload posted in
   * @param openingInventoryValueMinor the opening value in minor units (may be zero)
   * @param openingEntryId the posted opening journal entry id, or {@code null} when the value was
   *     zero (no journal booked)
   * @param idempotencyKey the Idempotency-Key header this activation was recorded under
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static InventoryMethodConfig activate(
      String cutoverPeriod,
      Instant activatedAt,
      String currency,
      long openingInventoryValueMinor,
      UUID openingEntryId,
      String idempotencyKey) {
    InventoryMethodConfig config = new InventoryMethodConfig();
    config.id = UUID.randomUUID();
    config.method = METHOD_PERPETUAL;
    config.perpetualActive = true;
    config.cutoverPeriod = Objects.requireNonNull(cutoverPeriod, "cutoverPeriod");
    config.activatedAt = Objects.requireNonNull(activatedAt, "activatedAt");
    config.currency = Objects.requireNonNull(currency, "currency");
    config.openingInventoryValueMinor = openingInventoryValueMinor;
    config.openingEntryId = openingEntryId;
    config.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    return config;
  }

  public UUID getId() {
    return id;
  }

  public String getMethod() {
    return method;
  }

  public boolean isPerpetualActive() {
    return perpetualActive;
  }

  public String getCutoverPeriod() {
    return cutoverPeriod;
  }

  public Instant getActivatedAt() {
    return activatedAt;
  }

  public String getCurrency() {
    return currency;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public long getOpeningInventoryValueMinor() {
    return openingInventoryValueMinor;
  }

  public UUID getOpeningEntryId() {
    return openingEntryId;
  }

  /**
   * Whether {@code cutoverPeriod}/{@code currency}/{@code openingInventoryValueMinor} match a
   * candidate activation request — the replay-payload comparison (ADR 0037's opening-balance idiom,
   * narrowed to three scalars instead of a hashed line-set since this payload has no arbitrary
   * list).
   */
  public boolean matchesPayload(
      String candidateCutoverPeriod,
      String candidateCurrency,
      long candidateOpeningInventoryValueMinor) {
    return cutoverPeriod.equals(candidateCutoverPeriod)
        && currency.equals(candidateCurrency)
        && openingInventoryValueMinor == candidateOpeningInventoryValueMinor;
  }
}
