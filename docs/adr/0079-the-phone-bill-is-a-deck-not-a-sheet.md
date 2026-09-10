# 0079. The phone till's bill is an attached deck, not a sheet you open

- **Status:** Proposed
- **Date:** 2026-09-10
- **Deciders:** owner + Claude (frontend)
- **Related:** [ADR 0034](0034-pos-shell-verticals-are-adapters.md) (the pos-shell rule this obeys:
  stateless presentation, one component per surface), [ADR 0075](0075-navigation-and-overlay-contract.md)
  (Back pops — the deck collapses before the till is left), [ADR 0077](0077-neutral-ink-brand-replaces-deep-cyan.md)
  (the ink palette this is drawn in), [ADR 0026](0026-promotions-single-discount-collapse.md) /
  [ADR 0027](0027-loyalty-service-and-eventual-consistency-redemption.md) (why coupon + member are
  walk-in-only, which is what gates the deck's chips).
  Source design: `Native Till Android v2.dc.html` (Claude Design, 412×915 portrait).

## Context

Below 640px the restaurant till kept the ticket inside a sheet. Ringing an order was: tap an item,
tap a chevron, a full-screen sheet covers the catalog, check the line landed, close the sheet, tap
the next item. The check step and the catalog were never on screen at the same time, so confirming
what you just rang always cost a round trip through a surface that hid the thing you were about to
tap next.

The tablet already answers this with a permanent bill column. A 412px phone has no such width — but
it has height, and the till was spending it on chrome rather than on the ticket: a 56px ink status
band, a 64px bill-tabs strip, a 56px floating chip row, and a 96px summary bar that was mostly a
button to open the sheet. Four bands, ~270px, and the bill still wasn't visible in any of them.

Two structural facts made the sheet hard to simply "make smaller". The walk-in cart is a local
client array owned by `Pos.tsx`; an open bill is a server aggregate owned by `BillDetail.tsx`, with
its own mutations, split-check state and line-removal lockdown. They had grown two different
surfaces (`WalkInCartSheet`, `PhoneSheetContent`) that looked alike and behaved differently. And
`PhoneSheetContent` was taking seven props it never rendered — a catalog grid it had inherited and
dropped — so the phone's tile→bill path had quietly migrated to `Pos.tsx` without anyone deleting
the dead half.

## Decision

On phone the bill becomes a **deck attached to the bottom of the till**, never dismissed:

- **Collapsed (~218px, 164px below a 720px-tall viewport)** it shows the ticket's identity, the
  newest unpaid lines, the amount due and the pay verb. **Expanded (74dvh)** it becomes the whole
  ticket: steppers, price breakdown, bill actions, and the cancel row.
- There is **no "open the cart" step in any flow**. Tapping a tile appends and the deck shows it;
  the catalog stays visible and tappable behind the collapsed deck.

One component, `pos-shell/layout/BillDock`, renders it for **both** data owners: `Pos.tsx` passes
the walk-in cart, `BillDetail.tsx` passes the open bill. It follows the pos-shell rule — stateless
presentation, every money string pre-formatted by the caller — so the two owners keep their own
mutations and cannot drift into two dialects again. The decisions that *are* worth naming live in
`features/pos/lib/dockLines.ts` as pure, tested functions: which lines the collapsed deck peeks at
(the newest **unpaid** ones), what the due figure is called, and which action chips each owner gets.

The phone chrome shrinks to match: a **52px white identity band** (`PosPhoneHeader`) replaces the
ink status band, the search field pairs with a Tables button in one row, and the bill-tabs strip is
gone — the deck's own title is the ticket's identity and tapping it opens the same order switcher.
What the ink band pinned and the new header does not — leaving the till, printer status, operator
sign-in — moves into the till menu behind the ⋮. The outlet picker stays reachable as the header's
identity line (a new `variant="subtitle"`); tablet and up are untouched on every count.

Explicitly **out of scope**: the payment and receipt surfaces, the tablet layout, and the mockup's
separate Discount / Member artboards — the deck hosts the existing coupon, member and manual-discount
fields in its expanded body rather than growing two new overlays.

## Consequences

**The deck is always on screen, including on an empty till.** That is the point and it is also the
price: ~218px of a 915px phone is permanently the bill's, and on a 640px phone the collapsed deck
drops its line list entirely (a 20px viewport can only show a cropped half-row, which reads as
broken) and keeps the title, the total and the verb. Two tile rows still fit at 360×640.

**Two surfaces are deleted, and one dead path with them.** `PhoneSheetContent` is gone; so is
`BillDetail`'s catalog/modifier cluster (`handleItemTap`, `handleModifierConfirm`, its
`ModifierModal`, the category state), which was unreachable the moment the sheet stopped rendering a
grid. `WalkInCartSheet` survives for tablet only.

**Tiles can finally badge a count in bill mode.** They never could: `BillSummaryResponse` carries a
line count and no per-item detail, so the badge was hard-coded to 0. `BillDetail` now reports its
unpaid quantities up (`onUnpaidQtyByItem`), stamped with the bill they describe so a bill switch
cannot badge the new tiles with the old bill's counts. The callback is held in a ref — passed
inline, as callers naturally write it, it would otherwise re-run the effect whose own setState
re-renders the caller, which is an infinite loop rather than a slow path.

**Split is now written down as bill-only.** A split check charges an explicit subset of bill line
ids; the walk-in cart has no server-side lines to name. The old sheet was bill-only by construction
so the rule never needed stating — the shared deck makes it a rule, enforced in `dockActions` and
asserted in `dockLines.test.ts`.

**Back collapses the deck before it leaves the till** (ADR 0075 N1), for both owners.

**Bill mode needs its own door out, and the deck is it.** The sheet carried a back arrow; the deck's
title carries the order switcher instead — the same one the walk-in deck opens. That is not a nicety
on a phone: the bill-tabs strip and the summary bar are both tablet-and-up, so without it an open
bill is a dead end you can only leave through the ⋮ menu.

**Verification is a fixture-driven browser walk.** `scripts/mobile-shots.mjs` gains a POS pass —
no backend — that shoots the deck peeking and expanded, for the walk-in cart and for a
*partially paid* bill, in light/en and dark/id. The partially-paid fixture is deliberate: it is the
only state that exercises the deck's whole vocabulary at once (the "partly paid" badge, a dimmed
settled row, and "still owing" rather than "total").

**Deferred.** The mockup's Discount and Member artboards as real sheets; the carwash and barbershop
verticals still render their own phone surface and could adopt the deck; and the mockup's "Park"
chip is not built — its only job there is to open the parked tray, which the header's Incoming
button already does.
