package id.co.nativeapp.finance.tax.domain;

/**
 * The direction of a PPN return's net for a period (Phase 4 Tax / PPN, ADR 0017): whether the
 * company owes VAT or has a recoverable credit.
 *
 * <ul>
 *   <li>{@code PAYABLE} — output VAT ≥ input VAT: the net is owed to the tax authority (booked to
 *       VAT_PAYABLE, and settled when paid). A zero net is classed PAYABLE with nothing to settle.
 *   <li>{@code CREDITABLE} — input VAT &gt; output VAT: the excess input VAT is carried forward to
 *       the next period (the Indonesian default, <em>dikompensasikan</em>; booked to
 *       VAT_CREDIT_CARRYFORWARD). Nothing to settle. Refund (restitusi) treatment is SME-gated.
 * </ul>
 */
public enum NetDirection {
  PAYABLE,
  CREDITABLE
}
