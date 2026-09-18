# 0086. The register refuses to close over an open bill — and a POS write carries the credential it holds

- **Status:** Proposed
- **Date:** 2026-09-18
- **Deciders:** owner + Claude (restaurant, console)
- **Related:** [ADR 0036](0036-register-sessions-and-platform-channel-settlements.md) (register
  sessions — what a close reconciles), [ADR 0038](0038-daily-close-all-tender-and-inventory.md)
  (daily close v2 — the close screen this precondition joins; amended by pointer), [ADR 0049](0049-business-and-employee-apps-outlet-terminal-auth.md)
  (device terminal + owner elevation — the bearer rule here is its P3b, one target wider),
  [ADR 0069](0069-bill-row-pessimistic-serialization.md) (the bill row lock; the open-bill
  lockdown it cites, commit `9712104a`, is the cancel policy this ADR leaves untouched),
  [ADR 0075](0075-navigation-and-overlay-contract.md) (the confirm stacks above the switcher),
  [ADR 0079](0079-the-phone-bill-is-a-deck-not-a-sheet.md) (the deck — why the cancel row is not added to its
  collapsed state), [ADR 0082](0082-the-phone-home-reads-today-not-the-month.md) (the home's
  "Bill terbuka" door lands on the switcher this ADR gives a cancel action).

## Context

Two owner reports on 2026-09-18, one sentence each:

1. *"Open bill masih belum bisa dihapus/dibatalkan — yang tidak sengaja terbuka, terbuka terus."*
2. *"Setiap closing, semua open bill harus tertutup — gada open bill kalau cabang tutup."*

**The first was a bug, not the policy.** The open-bill lockdown (DEVLOG 2026-08-31) says a bill
WITH lines is cancelled by an owner or manager, an EMPTY one by anyone, and one with a paid split
check by nobody; the service enforces it (`BillWriter.requireOwnerOrManager`, 403
`bill-mutation-forbidden`). The Business Android app is a **device** login — an outlet credential
whose JWT is cashier-tier — and an owner elevates on top of it with "Masuk untuk mengelola", a
second, personal OIDC login (ADR 0049 P3b). The till computed the cancel affordance from the
*merged* roles, so the button lit up; but `useCancelBill` and `useRemoveLine` sent the request on
the default `'outlet'` bearer, the gateway stamped `X-Roles: cashier` from that token, and the
service refused. The owner saw "Aksi ini butuh owner atau manajer" while being the owner. Refund,
void and close-correction had been given `auth: 'personal'` deliberately; cancel had not.

Two smaller things made the bug feel like a rule. The cancel row exists only in the **expanded**
bill deck (ADR 0079 vocabulary) and never in split mode, while the places an owner actually
lands — the home's "Bill terbuka" door, the till's order switcher — had no cancel at all. And two
of the refusals a cancel can meet (`bill-line-reserved`, `bill-not-open`) rendered the server's
raw English detail.

**The second was a missing precondition.** `RegisterSessionWriter.close()` reconciled cash and
every tender (ADR 0038) and never looked at the `bill` table. A session closed over any number of
open tabs; the accidental ones stayed OPEN for good, counted by the phone home every morning.

Asked, the owner decided: the close is **blocked** while open bills exist (each is paid or
cancelled by a person; the close never sweeps them), and the cancel policy **stays** as it is.

## Decision

### 1. The close refuses while any bill at the outlet is OPEN

`close()` counts `bill.status = 'OPEN'` for the outlet **under the exclusive `CashWindowLock`**,
immediately after acquiring it and before anything is written, and throws
`RegisterSessionHasOpenBillsException` → 409 `register-session-open-bills` carrying
`openBillCount`. The count query lives on `RegisterSessionRepository` beside the cash terms that
already read `sale`/`payment_refund`/`gift_card_sale` — the register is the one place that
reconciles the outlet's day — rather than injecting `BillRepository` into the writer (a
`bill → register lock, register → bill repository` cycle for no gain).

Why under the exclusive lock: every SHARED holder that flips a bill to PAID (`payBill` →
`recordCheck`, gateway capture) has either committed or is blocked behind the close, so the count
is the truth at the instant the close would snapshot. A bill whose lines are reserved by a live
QRIS payment is still OPEN and blocks the close — correct: money is in flight.

Why before any write: the refusal rolls back without consuming `close_idempotency_key`, so the
console's stable `close:<sessionId>` key retries cleanly once the bills are settled. The same-key
replay branch stays ahead of the guard: a close that already happened still replays 200.

**`BillWriter.open` joins the SHARED side** of the lock, so a bill cannot be opened "under" a
close that has already counted (open either commits first and blocks the close, or queues behind
it). Deadlock-safe: open takes no row lock; the close takes the session row lock then the
advisory lock and never locks bill rows. **`cancelBill` stays lock-free**: its race with a close
is conservative — an uncommitted cancel leaves the bill OPEN, the close refuses, the retry
succeeds.

**Parked and awaiting-payment orders are not "open bills."** A PARKED order is a saved cart — no
sale, no revenue, nothing the close reconciles; an AWAITING_PAYMENT order is a digital tender
with a TTL that `PaymentChargeExpired` reverts. Neither blocks the close. (A POS-parked order
has no discard path at all today and does occupy a table; if the owner wants it swept at close,
that is a separate decision — a discard endpoint, a role rule, a tray control.)

The expected preview (`GET /register-sessions/{id}/expected`) carries `openBillCount` so the close
sheet can say so **before** the cashier counts: an amber block naming the bills (the switcher has
the rest), a door to the order switcher, the Close button withheld. An unknown count (the preview
loading or errored) never blocks — the server's 409 is the backstop, the `needsCountConfirmation`
philosophy. No new event: nothing downstream needs "the close was refused."

### 2. A POS write carries the strongest credential the login holds — `'elevated'`

`AuthTarget` gains `'elevated'` = the personal/elevation bearer when there is one, else the
outlet credential. `useCancelBill`, `useRemoveLine` and the self-order access hooks ride it. Not
`'personal'`: an un-elevated device has no personal bearer, and a bearerless call is a 401 that
trips the auth layer's recovery — yet a bare cashier may cancel an EMPTY bill. Not `'outlet'`:
the elevation is invisible to the server. The service keeps deciding; the call just stops hiding
who is asking. Side effect worth having: the bill's `updated_by` becomes the elevated owner's
actor, which is what the `CANCELLED_BILLS_WITH_ITEMS` leak detector attributes the cancel to. If
the elevation token lapses, `'elevated'` falls back to the outlet bearer and the server's 403
renders as `bills.errNeedsManager` — honest, not silent.

Not changed: `ManualDiscountGuard`'s console side is gated on *base* roles and its checkout feeds
the offline queue replay (ADR 0028) — widening that is a separate design.

### 3. The switcher gets a cancel action; the deck does not change

Each open-bill row in the order switcher carries "Batalkan tagihan" under the deck's own policy
(`billPermissions`; the summary's new `paidLineCount` withholds it on a partially-paid bill), one
explainer under the list for a cashier, and the shared `CancelConfirmDialog` above the switcher in
the till's own z-stack (Back peels the confirm, then the switcher — ADR 0075). Not the collapsed
deck: ADR 0079 makes the cancel row expanded-only vocabulary and the 218px deck is the pay verb's
space. Not a gesture: undiscoverable on touch, and nothing in ADR 0075 governs one.

## Consequences

- A register cannot be closed with an open bill at the outlet — by anyone, on any surface. The
  end-of-day habit becomes: settle the tabs, then count. The close sheet lists what is in the way
  and opens the switcher where it is resolved.
- An elevated owner on the Business app can cancel a bill with items and remove lines from the
  phone; a cashier still cannot (403, localised), and still can discard an empty bill.
- `bill-line-reserved` (a QRIS payment in flight) and `bill-not-open` read in the operator's
  language.
- The close's 409 and the preview's count degrade independently: a console ahead of its backend
  loses the preflight block but still meets the 409; a backend ahead of its console refuses
  closes the sheet did not warn about. Deploy backend first.
- Rollback is a code revert: no migration, no event, no schema.
- Verified by `RegisterSessionWriterTest` (refusal writes nothing, count after the lock, key
  unconsumed), `RegisterCloseOpenBillsGuardIntegrationTest` (Postgres: empty bill blocks until
  cancelled then the same key closes; PAID / owner-CANCELLED / other-outlet / other-tenant bills do
  not), `BillLockdownTest` (+ `paidLineCount`), console vitest (`selectBearerToken` `'elevated'`,
  `billProblem`, `registerErrors`, `closeGuard`), `nav-smoke` [12], `mobile-shots` pos pass, the
  overflow audit at 360/320.
