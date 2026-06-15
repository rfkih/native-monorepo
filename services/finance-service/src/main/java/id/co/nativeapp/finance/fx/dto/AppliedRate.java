package id.co.nativeapp.finance.fx.dto;

import id.co.nativeapp.money.FxRate;
import id.co.nativeapp.money.Provenance;
import id.co.nativeapp.money.RateType;

/**
 * An auditable record of one FX rate that was applied in a conversion — surfaced on the
 * presentation response so a reader can reproduce the figure EXACTLY (the two integer rate
 * components, the type, the provenance, and the effective-from date).
 *
 * <p>For a triangulated (USD-pivot) conversion the response carries TWO {@code AppliedRate} entries
 * (the {@code from->USD} and {@code USD->to} legs) plus a {@code pivotCcy} marker, so the
 * composition is fully visible. For a direct conversion {@code pivotCcy} is {@code null}.
 *
 * @param fromCcy the leg's source ISO-4217 code
 * @param toCcy the leg's target ISO-4217 code
 * @param rateType the IAS-21 rate kind applied (CLOSING / AVERAGE / SPOT)
 * @param rateUnscaled the rate's unscaled integer value (value = unscaled / 10^scale)
 * @param rateScale the rate's decimal scale
 * @param provenance the trust level of the rate (STUB / LIVE / OFFICIAL)
 * @param effectiveFrom the effective-from date the resolved rate row carried, {@code null} for an
 *     identity (same-currency) rate that was never stored
 * @param pivotCcy the pivot currency code for a triangulated leg, or {@code null} for a direct rate
 */
public record AppliedRate(
    String fromCcy,
    String toCcy,
    RateType rateType,
    long rateUnscaled,
    int rateScale,
    Provenance provenance,
    String effectiveFrom,
    String pivotCcy) {

  /** Builds a DIRECT applied-rate record (no pivot) from a resolved {@link FxRate}. */
  public static AppliedRate direct(FxRate rate, String effectiveFrom) {
    return new AppliedRate(
        rate.from().getCurrencyCode(),
        rate.to().getCurrencyCode(),
        rate.rateType(),
        rate.unscaled(),
        rate.scale(),
        rate.provenance(),
        effectiveFrom,
        null);
  }

  /** Builds an applied-rate record for a triangulation LEG, tagging the pivot currency. */
  public static AppliedRate leg(FxRate rate, String effectiveFrom, String pivotCcy) {
    return new AppliedRate(
        rate.from().getCurrencyCode(),
        rate.to().getCurrencyCode(),
        rate.rateType(),
        rate.unscaled(),
        rate.scale(),
        rate.provenance(),
        effectiveFrom,
        pivotCcy);
  }
}
