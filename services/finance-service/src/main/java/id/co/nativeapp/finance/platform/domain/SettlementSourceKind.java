package id.co.nativeapp.finance.platform.domain;

import id.co.nativeapp.finance.gl.domain.AccountRole;

/**
 * Which GL account a receivable sub-ledger row's balance lives in (ADR 0076).
 *
 * <p>This is the "how the customer paid" half of the settlement model; the other half —
 * <em>who pays it out</em> — is the row's {@code source_code}, which is what a payout groups by. A
 * merchant on Shopee's QRIS is paid by Shopee in ONE transfer covering both its ShopeeFood orders
 * ({@link #MARKETPLACE}) and its counter QRIS sales ({@link #QRIS}); the two rows share a
 * source_code and differ by this kind, which is exactly what lets one payout credit two different
 * GL accounts correctly.
 *
 * <p><strong>The {@link AccountRole} is derived here and nowhere else.</strong> V61 deliberately
 * does not store the role per row: a stored role could drift out of agreement with its own kind,
 * and there is no reconciliation that would catch it.
 */
public enum SettlementSourceKind {
  /** Delivery/marketplace orders — the platform collects and pays later. GL 1250. */
  MARKETPLACE(AccountRole.PLATFORM_RECEIVABLE),

  /** QRIS at the counter — the acquirer collects and settles, typically H+1. GL 1901. */
  QRIS(AccountRole.QRIS_CLEARING),

  /** Card at the counter — the acquirer settles on its own cycle. GL 1902. */
  CARD(AccountRole.CARD_CLEARING);

  private final AccountRole accountRole;

  SettlementSourceKind(AccountRole accountRole) {
    this.accountRole = accountRole;
  }

  /** The role whose mapped account this kind's balances are credited against when settled. */
  public AccountRole accountRole() {
    return accountRole;
  }

  /**
   * The kind a {@code tender_type} wire value accrues under, or {@code null} for a tender that
   * carries no settlement receivable at all (CASH, an unknown tender, or a legacy null-tender sale
   * — those settle in the drawer, not through a payer).
   */
  public static SettlementSourceKind forTender(String tenderType) {
    if (tenderType == null) {
      return null;
    }
    return switch (tenderType) {
      case "ONLINE" -> MARKETPLACE;
      case "QRIS" -> QRIS;
      case "CARD" -> CARD;
      default -> null;
    };
  }
}
