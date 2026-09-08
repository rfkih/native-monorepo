package id.co.nativeapp.finance.platform.dto;

import java.util.List;

/**
 * What one PAYER still owes, across everything they settle (ADR 0076) — the settlement form's
 * opening question and the overdue nudge's input.
 *
 * <p>Grouped by payer rather than by channel because that is how the money arrives: Shopee pays
 * ShopeeFood orders and counter QRIS in a single transfer, so they belong in a single payout.
 * {@code outstandingMinor} may be NEGATIVE (a refund clawback after a full settlement — tolerated
 * by design, V44).
 */
public record PayoutSourceResponse(
    String sourceCode, String currency, long outstandingMinor, List<Line> lines) {

  /**
   * One sub-ledger row inside a payer's balance. {@code settleable} is false for a source this
   * payout form cannot clear yet (card, whose fee account is not mapped) — it is shown so the
   * balance is never silently missing, but it cannot be selected.
   */
  public record Line(
      String sourceKind, String channelCode, long outstandingMinor, boolean settleable) {}
}
