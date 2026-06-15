package id.co.nativeapp.finance.withinclose.dto;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of one within-company close attempt (P3d SEAM 4a).
 *
 * @param closeId the {@code within_company_close} row id (existing one on an idempotent re-close)
 * @param period the accounting period that was closed
 * @param firstClose {@code true} on the first close of this {@code (company, period)} — the close
 *     that emitted the events; {@code false} on an idempotent re-close (a clean no-op that emitted
 *     nothing)
 * @param reconciled whether the published trial balance reconciled (always true — the balancing
 *     equity closing line)
 * @param usesIllustrativeRules whether any rolled-up posting was illustrative-placeholder-derived
 * @param trialBalancePublishedGroups the groups a {@code TrialBalancePublished} was emitted for
 *     (empty when the company belongs to no group — it still emits {@code ConsolidationClosed})
 */
public record WithinCompanyCloseResult(
    UUID closeId,
    String period,
    boolean firstClose,
    boolean reconciled,
    boolean usesIllustrativeRules,
    List<UUID> trialBalancePublishedGroups) {}
