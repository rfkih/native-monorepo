package id.co.nativeapp.restaurant.sale.dto;

import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import id.co.nativeapp.security.ApiExceptionHandler;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;

/**
 * The application command to record a sale, assembled at the request edge from the {@code POST
 * /sales} body. The tenant ({@code company_id}) and actor are NOT here — they come from the bound
 * {@link id.co.nativeapp.tenant.TenantContext TenantContext}, never from the request body (rule 5).
 *
 * <p>The bean-validation constraints reject malformed input at the controller edge (via
 * {@code @Valid}) so a bad request fails with a {@code 400} from {@link ApiExceptionHandler} rather
 * than a downstream {@code 500}. {@code amountMinor} is {@link Positive} because a sale is the
 * GRAND TOTAL the customer pays — a zero or negative grand total is not a valid sale (a fully-
 * discounted order is rejected: "a fully-comped order is not a sale"). The currency is only checked
 * for presence here; its ISO-4217 validity is enforced by {@code libs/money} {@link
 * id.co.nativeapp.money.Money} (also mapped to {@code 400}).
 *
 * @param businessId the originating business unit
 * @param amountMinor the GRAND TOTAL in the currency's minor units (never a float); must be
 *     positive — this is the customer-pays amount after discount, service charge, and tax
 * @param currency the ISO-4217 currency code
 * @param occurredAt when the sale occurred; {@code null} means "happening right now" — {@link
 *     id.co.nativeapp.restaurant.sale.service.SaleWriter#create} resolves that default to {@code
 *     Instant.now()} itself, AFTER acquiring the per-business {@code CashWindowLock} (verified HIGH
 *     race fix), never before the transaction starts. A non-null value is explicit (possibly
 *     backdated) caller data, used as-is regardless of lock timing
 * @param idempotencyKey the client's request id, the producer-idempotency dedupe key
 * @param tenderType the payment tender type ({@code CASH}, {@code QRIS}, {@code CARD}), or {@code
 *     null} for legacy/no-payment sales; threaded to the {@code SaleRecorded} event wire field so
 *     finance can route the GL clearing account by tender (ADR 0006, slice 2). ALSO {@code null}
 *     when a gift card fully settled the sale (residual == 0, ADR 0027 decision — finance's
 *     null-tender fallback posts a ZERO clearing leg, which is omitted; see {@code
 *     OrderWriter}/{@code PaymentCaptureWriter})
 * @param breakdown the Phase 2 price breakdown (subtotal, discount, service charge, tax); null for
 *     legacy/carwash paths (all-null breakdown fields on the wire). Phase 4 (ADR 0027): when a
 *     loyalty redemption applies, this breakdown's {@code discount()} is the COMBINED deduction
 *     (promo + loyalty) — {@link
 *     id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema#toRecord} decomposes it back
 *     into the wire's promo-only {@code discount_minor} using {@code loyaltyRedeemedMinor}
 * @param loyaltyMemberId Phase 4 (ADR 0027): the loyalty member attached to this sale, or {@code
 *     null}
 * @param loyaltyRedeemedPoints Phase 4 (ADR 0027): the ACTUAL points redeemed, or {@code null}
 * @param loyaltyRedeemedMinor Phase 4 (ADR 0027): the currency value of the redeemed points, minor
 *     units, or {@code null}
 * @param giftCardId Phase 4 (ADR 0027): the gift card redeemed as a tender, or {@code null}
 * @param giftCardRedeemedMinor Phase 4 (ADR 0027): the ACTUAL amount redeemed from the gift card,
 *     minor units, or {@code null}
 * @param channel Phase B2 (ADR 0036): the sales-channel code this sale rang through, or {@code
 *     null}. Set ONLY when {@code tenderType} is {@code "ONLINE"} — the calling writer
 *     (OrderWriter/BillWriter) validates the channel exists and is active BEFORE assembling this
 *     command. Threaded straight to {@code SaleRecorded}'s wire {@code channel} field
 * @param soldByUserId ADR 0049 P4: the operator's Keycloak {@code sub} captured at RING TIME by
 *     {@code payment.service.PaymentWriter#recordPendingDigitalInCurrentTx} and stamped on the
 *     PENDING payment ({@code payment.sold_by_user_id}) — threaded here ONLY by {@code
 *     payment.service.PaymentCaptureWriter#capture} (the async {@code PaymentChargeSucceeded}
 *     consumer thread has no live operator session to read) so a digital sale still credits the
 *     RING-TIME operator instead of falling back to the bound actor. {@code null} for every other
 *     caller (a live operator session, read via {@code OperatorContextProvider}, always takes
 *     precedence over this field — see {@code SaleWriter}'s seller-resolution javadoc). Appended
 *     LAST, mirroring every prior additive field's positional-decode-safety discipline — this is an
 *     internal application command, never the HTTP request body (rule 5)
 * @param cogsMinor ADR 0067 Phase C: the Σ (depleted qty × moving-average unit cost) fold ({@code
 *     recipe.service.IngredientDepletionWriter.CogsResult}), computed by the caller from the SAME
 *     depletion call this sale's recording follows. {@code null} when the depletion carried no
 *     costed ingredients (or no depletion at all) — {@code SaleWriter} leaves {@code
 *     sale.cogs_minor} NULL and writes no {@code SaleCogsRecorded} event. Appended LAST alongside
 *     {@code cogsCurrency}, mirroring {@code soldByUserId}'s positional-decode-safety discipline
 * @param cogsCurrency ISO-4217 code of {@code cogsMinor}; required exactly when {@code cogsMinor}
 *     is non-null
 */
@SuppressWarnings("checkstyle:ParameterNumber")
public record RecordSaleCommand(
    @NotNull UUID businessId,
    @NotNull @Positive Long amountMinor,
    @NotBlank String currency,
    Instant occurredAt,
    @NotBlank String idempotencyKey,
    String tenderType,
    PriceBreakdown breakdown,
    UUID loyaltyMemberId,
    Long loyaltyRedeemedPoints,
    Long loyaltyRedeemedMinor,
    UUID giftCardId,
    Long giftCardRedeemedMinor,
    String channel,
    String soldByUserId,
    Long cogsMinor,
    String cogsCurrency) {

  /**
   * Convenience constructor preserving the original five-argument shape (no-tender / legacy
   * callers). Sets {@code tenderType} and {@code breakdown} to {@code null}, and every Phase 4/B2/
   * P4 field to {@code null}.
   */
  public RecordSaleCommand(
      UUID businessId,
      Long amountMinor,
      String currency,
      Instant occurredAt,
      String idempotencyKey) {
    this(
        businessId,
        amountMinor,
        currency,
        occurredAt,
        idempotencyKey,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Convenience constructor for callers with a tender type but no breakdown (Phase 1 / no-pricing
   * path). Sets {@code breakdown} and every Phase 4/B2/P4 field to {@code null}.
   */
  public RecordSaleCommand(
      UUID businessId,
      Long amountMinor,
      String currency,
      Instant occurredAt,
      String idempotencyKey,
      String tenderType) {
    this(
        businessId,
        amountMinor,
        currency,
        occurredAt,
        idempotencyKey,
        tenderType,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Convenience constructor for pre-Phase-4 callers with a tender type AND a breakdown. Sets every
   * Phase 4 (ADR 0027) field, {@code channel} (Phase B2), and {@code soldByUserId} (P4) to {@code
   * null}.
   */
  public RecordSaleCommand(
      UUID businessId,
      Long amountMinor,
      String currency,
      Instant occurredAt,
      String idempotencyKey,
      String tenderType,
      PriceBreakdown breakdown) {
    this(
        businessId,
        amountMinor,
        currency,
        occurredAt,
        idempotencyKey,
        tenderType,
        breakdown,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Convenience constructor for a tender type + breakdown + channel but no Phase 4 (ADR 0027)
   * loyalty/gift-card fields or P4 {@code soldByUserId} — the shape {@code BillWriter} uses (bills
   * do not yet support loyalty/gift-card redemption or digital-tender operator threading).
   */
  public RecordSaleCommand(
      UUID businessId,
      Long amountMinor,
      String currency,
      Instant occurredAt,
      String idempotencyKey,
      String tenderType,
      PriceBreakdown breakdown,
      String channel) {
    this(
        businessId,
        amountMinor,
        currency,
        occurredAt,
        idempotencyKey,
        tenderType,
        breakdown,
        null,
        null,
        null,
        null,
        null,
        channel,
        null,
        null,
        null);
  }

  /**
   * Convenience constructor for a tender type + breakdown + channel + the ADR 0067 Phase C COGS
   * fold — the shape {@code BillWriter} uses (bills do not yet support loyalty/gift-card redemption
   * or digital-tender operator threading, but DO deplete recipes and so may carry a COGS fold).
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public RecordSaleCommand(
      UUID businessId,
      Long amountMinor,
      String currency,
      Instant occurredAt,
      String idempotencyKey,
      String tenderType,
      PriceBreakdown breakdown,
      String channel,
      Long cogsMinor,
      String cogsCurrency) {
    this(
        businessId,
        amountMinor,
        currency,
        occurredAt,
        idempotencyKey,
        tenderType,
        breakdown,
        null,
        null,
        null,
        null,
        null,
        channel,
        null,
        cogsMinor,
        cogsCurrency);
  }
}
