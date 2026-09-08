# 76. Settle by who pays you, not by how the customer paid

- **Status:** Proposed
- **Date:** 2026-09-08
- **Deciders:** owner + Claude (tech-lead)
- **Related:** extends [0036](0036-register-sessions-and-platform-channel-settlements.md) §4–5;
  keeps the Dr-BANK invariant of [0016](0016-bank-reconciliation.md); QRIS modes from
  [0045](0045-qris-modes-and-payment-service.md). Touches rule 8 (money) and the GL posting shape.

## Context

ADR 0036 gave marketplace money a dimension: `platform_receivable` is a per-`(company, channel_code,
currency)` accumulator, a sub-ledger of GL 1250 that the GL itself does not carry. QRIS got no such
thing — every QRIS sale lands in one company-wide account, 1901 QRIS Settlement Clearing (ADR 0045 /
V15), with no record of *who* is going to pay it out.

That asymmetry breaks on a real merchant. A merchant using **Shopee's QRIS** is paid by Shopee in a
**single bank transfer** covering both ShopeeFood orders (1250) and QRIS-at-the-counter sales (1901).
Today that one transfer has to be recorded twice, through two unrelated screens: the settlement form
for the receivable, and bank reconciliation's `QRIS_CLEARING` category for the clearing account. The
same merchant may later run QRIS through GoPay, Xendit or Midtrans — each with its own payout cycle —
and the model has nowhere to put that fact.

Two constraints shape the options:

- **Bank reconciliation is the only writer that debits BANK** (ADR 0016, restated in 0036 §5).
  A settlement debits CASH_CLEARING; the payout's statement line then sweeps to BANK. Any design
  that has the settlement form debit BANK directly would break that single-writer rule.
- **1901 was deliberately separated from 1250** (ADR 0045): digital clearing is not a marketplace
  receivable, and collapsing them would lose that distinction fleet-wide.

Options considered: (a) treat QRIS as just another channel on 1250 — one account, simplest UI, but
discards the 0045 separation; (b) give QRIS its own parallel settle form — smallest change, but a
Shopee payout still has to be entered twice; (c) make the settlement dimension the **payer**, and let
one payout clear several sources at once.

## Decision

We will make the settlement dimension **who pays the merchant** (the settlement *source*), not which
tender the customer used, and record **one payout as one settlement with several lines**.

1. **Source-dimensioned sub-ledger.** The `platform_receivable` accumulator gains two columns, which
   answer two different questions:
   - `source_kind` (`MARKETPLACE | QRIS | CARD`) — **which GL account** the row's balance lives in.
     Marketplace rows keep crediting `PLATFORM_RECEIVABLE` (1250); QRIS rows credit `QRIS_CLEARING`
     (1901); card rows `CARD_CLEARING` (1902). The `AccountRole` is **derived** from this value in
     one place in the writer and never stored per row — a stored role could drift out of agreement
     with its own kind.
   - `source_code` — **who pays**, the grouping key for a payout. `SHOPEE` owns both the ShopeeFood
     channel and the Shopee QRIS row, so one settlement clears both.

   The existing unique key `(company_id, channel_code, currency)` is deliberately **left alone**.
   Widening it to include `source_kind` would be the tidier shape, but the previous image upserts
   with `ON CONFLICT` on exactly that key, so dropping it would break a rollback onto the new
   schema. A QRIS row instead carries a STABLE namespaced `channel_code` (`TENDER:QRIS`) and names
   the payer through `source_code`. The channel_code is deliberately NOT derived from the payer:
   deriving it would mean re-pointing QRIS from one acquirer to another created a SECOND row and
   stranded the balance on the first. One row exists per tender family and its `source_code` moves
   with the configuration — which is why naming a payer also re-points the existing balance in the
   same transaction. Both columns are added with defaults and no `SET NOT NULL`, so the migration
   passes `check-migration-safety.sh` and an old image keeps inserting successfully.
2. **A payout is a header plus lines.** The header holds the payout date, the source, and the
   **net actually received** (the figure the merchant reads off the bank statement, entered once).
   Each line names one receivable and its **gross**. `fee = Σ gross − net`, and `net > Σ gross`
   stays a 422 as in 0036 §5.
3. **Fee is allocated pro-rata by line gross** to that line's fee account —
   `PLATFORM_FEE_EXPENSE` (5710) for marketplace lines, `QRIS_FEE_EXPENSE` (5720) for QRIS lines —
   so "QRIS fee" keeps meaning something instead of being absorbed into "marketplace fee". Rounding
   remainder goes to the largest line, so the legs stay exact (rule 8: integer minor units).
4. **The journal keeps 0036's shape and 0016's invariant**:
   `Dr CASH_CLEARING (net) + Dr <fee account> (allocated fee, per line) / Cr <line's receivable
   account> (gross, per line)`. Zero-amount legs omitted. **Bank reconciliation remains the only
   Dr-BANK writer** — recording a payout does NOT put money in 1000 Bank.
5. **Overdue is per source, not a daily alarm.** Each source KIND carries an expected cadence in
   days (marketplace 8, QRIS 3, card 4 — the usual payout cycle plus slack for weekends); a payer
   spanning several kinds takes the LONGEST of them, because Shopee settles its marketplace and
   QRIS money in one weekly transfer and the QRIS cadence would nag about money that is not due.
   A payer is *overdue* when it still owes something and its LAST PAYOUT is older than that cadence,
   or it has never paid out at all — age is measured from the payout, not from the balance, because
   `updated_at` moves on every sale and a busy channel would look freshly touched forever. A
   per-source cadence override is a follow-up; v1 applies these defaults to every merchant. The console
   surfaces this as a card on Beranda that appears **only when something is overdue**, naming the
   source, the amount and a link to the form. There is deliberately **no daily popup**: cycles
   differ per platform (QRIS commonly H+1, GoFood daily, Shopee weekly, all shifting around
   holidays), so a fixed daily prompt would fire mostly on days when nothing is due and train the
   reader to dismiss it.
6. The card's wording says the payout is **recorded as settled**, never "in the bank" — that only
   becomes true after reconciliation (see 4).

**Out of scope.** Splitting the fee into commission / transaction fee / merchant-funded promo stays
deferred, exactly as 0036 §5 deferred platform subsidies: `gross − net` is derived from a figure the
merchant can verify on the statement, whereas a breakdown depends on reading the platform's own
report and would need its own expense account (merchant-funded promo is marketing spend, not a
platform fee — booking it to 5710 would misstate the P&L). Also out of scope: reconciling the bank
line from inside the settlement form, and per-sale QRIS acquirer attribution for a merchant running
two acquirers at once (v1 attributes QRIS to the company's configured source at posting time).

## Consequences

- One bank transfer becomes one entry. The merchant answers "how much actually reached my account"
  at the moment of recording, from the number they can see, without knowing any MDR or commission
  rate.
- QRIS gains the per-source granularity marketplace money has had since 0036, without collapsing
  1901 into 1250.
- The settlement writer grows a loop and a rounding rule; its guarded `outstanding >= gross` update
  and per-source advisory lock now apply **per line**, and the Idempotency-Key replay-by-key-first
  path must compare the whole line set, not a single amount — a replayed key with different lines
  is a 409, as elsewhere in 0036.
- Existing single-source settlements must keep reading: the migration backfills one line per
  historical settlement row.
- A merchant running two QRIS acquirers simultaneously is mis-attributed in v1 (all QRIS goes to the
  configured source). Detectable — the sub-ledger will show a balance that never settles — and
  fixable additively by carrying the acquirer on the sale.
- The Beranda card is only as good as the cadence configured per source; a wrong cadence makes it
  either silent or nagging. It is a nudge, not a control: nothing blocks on it.
- **KNOWN RISK — the QRIS balance now has two writers that credit it.** Recording a payout credits
  1901; so does reconciling a bank line under the existing `QRIS_CLEARING` category (ADR 0045),
  which is the flow merchants have been using. Doing both for the same transfer credits 1901 twice
  and doubles the MDR expense. v1 mitigates with wording only — the payout confirmation names the
  plain CLEARING category as the follow-up step — because a hard interlock needs the payout and the
  statement line to know about each other, which is the deferred "reconcile from inside the
  settlement form" item above. Until then the wrong click is one step away, and it is the one the
  merchant has been trained on.
- The accrual basis is the sale's NET TENDER (`amount − gift_card_redeemed`), stored on the journal
  entry by V64 so a void unwinds by exactly what it accrued. Using the grand total — correct while
  only ONLINE accrued, since ONLINE cannot carry a gift-card leg — would have driven 1901 negative
  on every QRIS sale part-paid by a gift card.
- As throughout 0036, all account codes are ILLUSTRATIVE until an SME remaps `role_account_map`.
