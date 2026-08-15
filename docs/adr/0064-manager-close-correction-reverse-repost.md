# 0064. Manager/owner correction of a completed closing — reverse + re-post, never mutate

- **Status:** Accepted
- **Date:** 2026-08-15
- **Deciders:** rifki, Claude (Opus 4.8)
- **Related:** [0036](0036-register-sessions-and-platform-channel-settlements.md) (register sessions / closing kasir + the cash-variance GL), [0038](0038-daily-close-all-tender-and-inventory.md) (per-tender close + stocktake shrinkage), [0056](0056-moving-average-inventory-cost.md) (moving-average inventory), [0061](0061-manager-gated-pos-sale-return.md) (manager-gated reversal precedent), [0017](0017-tax-ppn-vat-return.md) (period sealing = a filed `tax_filing`)

## Context
A cashier closes the branch (tutup kasir) with the **wrong counted cash**, and/or fills the **stock opname** with wrong counts. Owners want a **manager/owner-only** way to correct a *completed* closing. Both closings are not just forms: a register close posts a **cash over/short journal entry** to finance, and a stocktake posts a **shrinkage** entry. A naïve "edit the number" would silently corrupt the books. The register close is also one-way and immutable at three layers (domain guard, pessimistic row-lock, `ck_crs_closed_shape` + `uq_crs_close_idem`), and finance dedupes by `source_event_id` — so simply re-emitting a close would **double-post**.

Two forces: (1) money integrity — a correction must keep the ledger balanced and auditable; (2) the two subsystems differ. Finance already has proven reverse/supersession primitives (void/refund contra `ReversalPostingWriter`; payroll run-seq supersession with `ReversalEventIds`) — just not wired to register close. And the ingredient opname is **already self-correcting**: `Ingredient.setStock` is absolute (preserves the moving average) and stocktake variance is computed vs the *current* system qty, so a fresh opname emits a *compensating* shrinkage delta.

## Decision
**Amend in place at the aggregate; reverse + re-post at the ledger; recent/unsealed only; owner/manager only.** The chosen scope (owner decision): correct **both** cash and stock, **amend-in-place** (never a re-open), **recent/unsealed** closings only.

**Cash close correction** — the money-critical path:
- New `POST /api/v1/register-sessions/{id}/correct-close` (owner/manager at the gateway, a `@Order(HIGHEST_PRECEDENCE)` OPS_ROLES carve-out before the POS_ROLES register route; the UI rides the personal/elevated bearer like a refund). Body: corrected counted cash + a **required reason**.
- restaurant-service re-derives `over_short = counted − expected` (expected is historical — only the physical count moved), **amends the CLOSED session in place** (counted, over/short, a bumped `close_seq`; prior values survive in the Auditable/CDC trail), and re-emits `RegisterSessionClosed` through the outbox **marked** with `supersedes_event_id` (the prior close/correction event id, stored on the session as `close_event_id`) + `close_seq` + `reason`. The event evolves **additively** (`supersedes_event_id`/`close_seq`/`reason`, defaults null/1/null). Per-tender lines are re-emitted unchanged (cash-only correction), so finance's reverse-whole/re-post-whole nets the tenders to zero and moves only the cash leg.
- finance-service, on a `supersedes_event_id`, loads the prior variance journal by `source_event_id`, posts a **balanced contra** (each leg negated, deterministic `ReversalEventIds` id so a re-delivery can't double-reverse), then posts the corrected variance — both append-only, both under the correction event's `processOnce` claim, both **sealed-period-guarded** (a filed period quarantines the whole correction to the accountant's error inbox).
- **Recent-only:** restaurant-service rejects a close older than **62 days** (`422`, a coarse proxy since it can't see finance's seal across the DB boundary — finance is the authority) and a pre-0064 close with no `close_event_id`. Correcting to the recorded value is a no-op.

**Stock opname correction** — reuse the self-compensating opname:
- No new endpoint/event. A manager/owner-gated affordance re-opens the outlet's **existing opname** (`POST /api/v1/ingredient-stocktakes`) pre-filled with current counts; submitting the true counts sets stock absolutely (moving average preserved) and emits a compensating `StocktakeCompleted` that finance posts as a netting shrinkage entry **in the current open period**.

**UI:** both hang off the ClosingHistorySheet (ADR-0063-era owner/manager browse). A per-row "Perbaiki" opens `CloseCorrectionSheet` — a cash re-count (input + reason → reverse/re-post) and a "Perbaiki stok opname" that hands off to the outlet opname. i18n en+id.

**Out of scope:** correcting per-tender counts (cash-only v1); re-opening a drawer; correcting a sealed/filed period in-app (accountant's adjusting entry).

## Consequences
- **Rule code now follows:** a completed close is corrected by **reverse + re-post** (append-only ledger, rule 4), never by mutating a posted journal or the closed session's money; the session row is amended in place with the before-value preserved by CDC + `updated_by`. Money stays integer minor units (rule 8); the correction endpoint is owner/manager at the gateway (the real boundary), UI-gated to match.
- **Enforced by / tested:** `RegisterCloseWriterTest` (the contra negates every leg of the prior variance; balanced); `RegisterCloseCorrectionIntegrationTest` (real Postgres — the session is amended, `close_seq` bumped, a second `RegisterSessionClosed` is emitted with `supersedes_event_id` = the original event, corrected figures + reason; OPEN → 409; correcting to the recorded value → no-op, no new event); `RegisterSessionClosedContractTest` (additive fields round-trip). Static: no-SELECT-\* + spotless.
- **Costs / follow-ups:** a correction to a **sealed** period is quarantined to the accountant, not posted (surfaced as an error-inbox item) — the manager should be told. The 62-day bound is coarse (restaurant-service can't see finance's seal). Per-tender correction and a re-open flow are deferred. The stock correction posts its compensating shrinkage into the **current** period (standard adjusting-entry behavior), not the original day.
