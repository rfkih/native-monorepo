# 27. Loyalty and gift cards — a new bounded context, and redemption under eventual consistency

Date: 2026-07-31

## Status

Accepted

## Context

Phase 4 of the POS-parity program. A company-wide loyalty balance and gift cards must work across
all three vertical POSes, under two hard rules that pull against each other: business services may
never call each other synchronously (rule 2), yet a redemption must be checkable at sale time. And
the money side is real: an outstanding gift card is a liability; redeemed points reduce revenue.
Odoo solves this inside one database; Native cannot.

## Decision

1. **A new `loyalty-service` owns the ledger of record** — members, an append-only points ledger,
   gift cards and their ledger. It is the ONLY home of member PII (phone/name column-encrypted, an
   HMAC phone hash for exact-match lookup); no event ever carries PII, and verticals never store
   it. Putting the ledger in any vertical would couple the other two to it; org-service owns
   identity, not money-adjacent ledgers; finance owns derived GL, not operational balances.

2. **The console→gateway→loyalty-service call is a CLIENT call, not service-to-service.** Member
   lookup/enroll and gift-card lookup are direct, fresh, and authoritative at the till — exactly
   like the console calls any service. Rule 2 is untouched: the *verticals* never call
   loyalty-service; at sale time they validate and clamp against LOCAL read models
   (`member_balance_ref`, `gift_card_ref`) fed by `LoyaltyBalanceChanged` /
   `GiftCardStateChanged` — absolute-value events with a monotonic `balance_seq`
   (set-if-newer upsert), so caches self-heal regardless of delivery order.

3. **Redemption under eventual consistency — clamp locally, reconcile authoritatively,
   flag overdrafts.** The checkout clamps a redemption to the cached balance and atomically
   decrements the cached row (zero rows → 409), making double-spend impossible *within one
   vertical service*. Cross-outlet/cross-vertical races within replication lag can overdraft: the
   authoritative ledger (loyalty-service consuming `SaleRecorded`) applies the redemption anyway,
   lets the balance go NEGATIVE, and emits `LoyaltyRedemptionFlagged` for manual follow-up. This
   is the pragmatic, Odoo-comparable choice: a reservation saga would put a customer-visible
   async wait at the till to bound an exposure of one redemption per replication-lag window.
   The saga is the documented escalation path if real-world flag volume demands it.

4. **Accounting treatment (ILLUSTRATIVE, SME-gated end to end):**
   - **Gift-card redemption is a TENDER settlement, not a discount.** Revenue legs are unchanged;
     the clearing debit splits: `Dr GIFT_CARD_LIABILITY` for `gift_card_redeemed_minor` + the
     tender clearing for the residual. The reconciliation identity is untouched by gift cards.
   - **Points redemption is contra-revenue** (`LOYALTY_DISCOUNT`), extending the identity to
     `subtotal − discount − loyalty_redeemed + service_charge + tax == amount` (nulls → 0, so
     every pre-Phase-4 event is unchanged).
   - **Earn is memo-only** — no GL at earn. Accruing a points liability requires valuing points
     (rate + breakage), squarely SME territory; `LOYALTY_LIABILITY` and
     `GIFT_CARD_BREAKAGE_INCOME` are seeded defined-but-unused (the `SERVICE_CHARGE_PAYABLE`
     precedent) for the SME-confirmed model.
   - **A gift-card SALE is not revenue**: `GiftCardSold` (a new event — never merged into
     `SaleRecorded`) posts `Dr <tender clearing> / Cr GIFT_CARD_LIABILITY` in finance.

5. **Event shape:** `SaleRecorded` gains 5 nullable fields (member id, redeemed points, redeemed
   value, gift-card id, gift-card settlement amount) — rule-7 additive, every consumer keeps
   working. Four new events (`GiftCardSold`, `LoyaltyBalanceChanged`, `GiftCardStateChanged`,
   `LoyaltyRedemptionFlagged`) registered with catalog entries and contract tests. Gift-card ids
   are minted at the till (UUID; the human code derived deterministically from it) so no
   cross-service numbering coordination exists; a card becomes redeemable when `GiftCardSold`
   replicates (seconds — redemption typically happens much later).

6. **Voids/refunds:** loyalty-service consumes the existing `SaleVoided`/`SaleRefunded` and
   reverses that sale's earn/redeem entries from its own stored facts — full reversal only,
   matching finance's posture.

## Consequences

- The double-spend exposure is bounded and visible (flag + negative balance), never silent; the
  ledger is append-only and idempotent by event id with a unique backstop.
- Earn rules are company-wide config in loyalty-service; the POS shows an estimate that can drift
  briefly after a config change.
- Phone is the only member key — no dedupe/merge across typos or changed numbers this phase.
- The accounting treatment can be swapped by an SME through reference data (roles/templates)
  without touching the event stream.
- The gateway gains `/api/v1/loyalty/**` (POS roles) with the earn-rules admin carved out to the
  dashboard; the dev stack needs the loyalty postgres role/DB and Debezium connector.
- Security review (2026-07-31, PASS): fixed before close — the phone hash is tenant-salted
  (cross-tenant linkage on a DB dump), the lookup moved off the URL into a POST body, the
  gift-card code became a KEYED derivation (`NATIVE_GIFTCARD_CODE_KEY`, fleet-matched — the code
  is a bearer secret and must not be a public function of a broadcast id), card enumeration is
  owner/manager-only, and minting requires a tender type under a configurable ceiling
  (`NATIVE_GIFTCARD_MAX_MINT_MINOR`). Accepted with note: the exact-match member phone lookup is
  a within-tenant staff capability by design; gateway-header trust off-gateway is the
  pre-existing fleet posture (mTLS at split time). **Follow-up:** digital-tender gift-card sales
  should route through the pending→capture flow so a card becomes redeemable only once tender is
  captured; a compensating un-redeem on void; the fleet-wide literal hash-chain for the journal
  remains a platform gap predating this phase.
