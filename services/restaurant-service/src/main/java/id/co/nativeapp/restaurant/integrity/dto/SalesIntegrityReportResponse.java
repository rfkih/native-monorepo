package id.co.nativeapp.restaurant.integrity.dto;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One outlet's sales-leak report for a window ({@code GET /api/v1/sales-integrity/report}).
 *
 * <p><strong>The headline is a RANGE, not a number.</strong> {@code estimatedLeakMinorLow} counts
 * only what is tightly quantified — tracked items counted short, where one missing unit is one
 * unrecorded sale at one known price. {@code estimatedLeakMinorHigh} adds the ingredient-shortfall
 * estimate, which is real but has innocent explanations (waste, spoilage, staff meals,
 * over-portioning) that Native cannot yet record and net out. Collapsing the two into a single
 * figure would present an inference with the confidence of a measurement.
 *
 * <p>A low bound of 0 is therefore normal and correct at an outlet whose only evidence is
 * ingredient shrinkage — it says the confident floor is zero, not that nothing is wrong.
 *
 * @param businessId the outlet reported on
 * @param from window start, inclusive
 * @param to window end, exclusive
 * @param currency the ISO-4217 code every money figure here is in, or {@code null} when the window
 *     produced no valued finding at all
 * @param estimatedLeakMinorLow the confident floor, in minor units (never a float)
 * @param estimatedLeakMinorHigh the upper bound, in minor units
 * @param confirmedMissingCostMinor what the vanished stock COST — not an estimate: the
 *     moving-average value the stocktakes already computed (ADR 0056). The one hard number here
 * @param signals every signal that actually fired, most severe first; empty means nothing stood out
 * @param coverage what this report could not see — read it before trusting a small total
 */
public record SalesIntegrityReportResponse(
    UUID businessId,
    Instant from,
    Instant to,
    @Nullable String currency,
    long estimatedLeakMinorLow,
    long estimatedLeakMinorHigh,
    long confirmedMissingCostMinor,
    List<LeakSignalResponse> signals,
    LeakCoverageResponse coverage) {}
