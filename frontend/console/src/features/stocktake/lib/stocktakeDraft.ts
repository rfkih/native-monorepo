/**
 * stocktakeDraft.ts — what the operator is about to submit, summarised BEFORE it is submitted.
 *
 * A stock opname posts valued shrinkage to the general ledger, and on a phone the list never fits
 * one screen: the count that decides that posting was only ever visible AFTER the fact, on the
 * result summary. This computes the same three numbers up front — how many lines differ, how many
 * are unusable, and what the difference is worth — so the footer can show them next to the button
 * that commits them.
 *
 * Pure (no react-query / no DOM), the same shape as ./stocktakeVarianceGuard. Money (rule 8) stays
 * integer minor units; quantities are BASE units, exactly as the submit payload carries them.
 */

export interface StocktakeDraftLine {
  /** On-hand quantity before this count, in the ingredient's BASE unit. */
  systemQty: number
  /** The operator's count in BASE units, or `null` when the field is blank/unparseable — a line
   *  that blocks submit rather than being posted as some guessed number. */
  countedQty: number | null
  /** Per-BASE-unit cost in minor units; null = uncosted (counted, but worth nothing to the books). */
  unitCostMinor: number | null
  /** The line's own cost currency. */
  currency: string
}

export interface StocktakeDraftSummary {
  /** Lines whose count differs from the system quantity — the ones that will move stock. */
  changed: number
  /** Lines that cannot be submitted (blank or unparseable). */
  invalid: number
  /**
   * Net value of the variance in minor units of the requested currency. Carries the RAW variance
   * sign, like a line's `varianceValueMinor` on the response: NEGATIVE = net shortage (a loss),
   * positive = net overage. Invalid lines contribute nothing.
   */
  netValueMinor: number
  /**
   * True when a costed line was left OUT of `netValueMinor` because it is priced in another
   * currency. Mixing currencies into one figure would be rule 8's exact failure mode, so the
   * caller shows the total as partial instead of quietly under-reporting it.
   */
  partialValue: boolean
}

/**
 * Summarises a draft count. `baseCurrency` is the company's — only lines priced in it join the
 * money figure (see `partialValue`).
 */
export function summarizeStocktakeDraft(
  lines: StocktakeDraftLine[],
  baseCurrency: string,
): StocktakeDraftSummary {
  let changed = 0
  let invalid = 0
  let netValueMinor = 0
  let partialValue = false

  for (const line of lines) {
    if (line.countedQty == null) {
      invalid += 1
      continue
    }
    const varianceQty = line.countedQty - line.systemQty
    if (varianceQty === 0) continue
    changed += 1
    if (line.unitCostMinor == null) continue
    if (line.currency !== baseCurrency) {
      partialValue = true
      continue
    }
    netValueMinor += varianceQty * line.unitCostMinor
  }

  return { changed, invalid, netValueMinor, partialValue }
}
