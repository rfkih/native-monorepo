package id.co.nativeapp.restaurant.register.projection;

/**
 * Single-row read projection over the {@code sale} rows in a register session's window — the
 * aggregate figures the POS daily summary (Z-report) prints. Backs {@code
 * RegisterSessionRepository.summarizeSales}, a native aggregate query whose snake_case aliases map
 * to these getters.
 *
 * <p>All money amounts are integer minor units of the session currency (rule 8). Every component is
 * summed from the per-sale breakdown SNAPSHOT (V39) — never re-derived — so the figures respect
 * each sale's own effective tax rule and carry no second rounding. The full identity {@code
 * grossSales − discount − loyaltyRedeemed + serviceCharge + tax == total} holds in aggregate (it
 * holds per sale, and legacy no-breakdown rows fall back to {@code subtotal == amount}, keeping the
 * identity trivially true).
 *
 * <p>Lives in its own {@code projection} package — a read model is neither the write-side domain
 * entity nor a request/response DTO.
 */
public interface SaleSummaryView {

  /** Number of tendered sales in the window (the transaction count). */
  long getTxnCount();

  /** Σ subtotal (pre-discount menu price); legacy rows fall back to {@code amount_minor}. */
  long getGrossSalesMinor();

  /** Σ promo-only discount (loyalty is a separate line). */
  long getDiscountMinor();

  /** Σ service charge. */
  long getServiceChargeMinor();

  /** Σ tax (PB1). */
  long getTaxMinor();

  /** Σ loyalty-points contra-revenue redemption. */
  long getLoyaltyRedeemedMinor();

  /** Σ grand total charged (gross of refunds — refunds are a separate ledger). */
  long getTotalMinor();

  /** True when ANY sale in the window used an illustrative tax/service-charge rule. */
  boolean getUsesIllustrativeRules();
}
