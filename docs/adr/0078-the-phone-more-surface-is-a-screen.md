# 0078. The phone's "More" surface is a screen, not a modal

- **Status:** Accepted
- **Date:** 2026-09-09
- **Deciders:** owner + Claude (frontend)
- **Amends:** [ADR 0075](0075-navigation-and-overlay-contract.md) — rule N3 keeps its force; this
  records that More was never a modal in the first place, and says where the line sits.
- **Related:** [ADR 0052](0052-preset-role-based-access.md) (the personas whose bar this is),
  [ADR 0077](0077-neutral-ink-brand-replaces-deep-cyan.md) (the design migration it arrived with);
  `frontend/console/scripts/nav-smoke.mjs` is the executable form of the navigation contract.

## Context

Below the 640px cutoff the Shell's sidebar and its hamburger are both hidden. For an
owner/manager/accountant/HR login on a phone, the bottom bar's **More** entry is not a convenience
menu — it is the *only* navigation they have. Everything the desktop sidebar offers (eleven groups,
33 links at an owner's grant) is reachable through it and nowhere else.

It was built as a bottom sheet, and a modal turns out to be the wrong container for that job in
three concrete ways, all of them consequences of `MobileTabBarGate` holding the open state as
`moreAnchor === pathname`:

- **It self-dismisses on every use.** Opening it, tapping a link, and wanting the next link means
  opening it again from the top. That is the common case, not the edge case: someone navigating a
  back office visits several pages in a row.
- **It cannot be backed into.** Hardware Back closes the sheet, so the surface a phone login spends
  the most time in has no place in history. There is no way to return to it.
- **It loses scroll position.** The sheet remounts each time, so a persona whose group sits low in
  the tree re-scrolls past everything above it on every visit.

None of these is a bug in the sheet. They are the correct behaviour *of a modal*, applied to
something that is not one.

ADR 0075 N3 says every modal goes through `components/ui/Dialog`. The question this raises is
whether More is an exception to N3 or simply not a modal. It is the latter: a modal is a
transient, focus-trapping interruption that returns you to what you were doing. More is a
destination you navigate to, spend time in, and navigate onward from.

## Decision

We will make More a **routed screen** at `/more`, and the More tab an ordinary route tab rather
than an action tab.

- The route is **phone-only**: at 640px and up it redirects to the login's home, exactly as
  `/me/payslips` and `/me/timeoff` already do — above the cutoff the sidebar is the navigation and
  a full-screen link list would be a worse duplicate of it.
- It is gated on the same office capability that renders the tab, so a URL cannot reach a surface
  the bar would not offer.
- The tab bar stays mounted on it (`shouldMountTabBar` already returns true), satisfying N2: More
  is a page of the same app, with the same chrome.
- The two overlays it launches (stock opname, register close) move from the tab-bar gate onto the
  page that offers them. They remain `Dialog`-family overlays under N3 — closing one returns you
  to `/more`, which is now a real place to return to.

The three defects above are then answered by rules the console already has, rather than by
bespoke handling: **N1** gives Back a pop to wherever you came from, **N4** lands a PUSH at the
top and restores the offset on POP, and staying mounted as a route is what stops the self-dismiss.

Explicitly **out of scope**: the desktop sidebar is unchanged; N3 still governs every actual modal,
and this is not a licence to promote other overlays to routes — the test is whether the surface is
a destination or an interruption.

## Consequences

**More gains a history entry.** That is the point, and it is also the cost: Back from the first
page you open out of More returns you to More rather than to where you started. This is the
behaviour of every list→detail pair in the app, so it is what the rest of the console already
teaches, but it *is* a change from the sheet, which vanished and left no trace.

**The Android shells inherit it correctly, and that is visible.** The back-guard parks a sentinel
above every route (ADR 0075); More is now an ordinary route, so it takes part in that arithmetic
instead of sitting outside it as a self-popping overlay — one fewer special case in the
hardest-to-reason-about part of the navigation. The user-visible consequence: inside the shells,
hardware Back on More now raises the "leave this page?" confirm it raises on every other page,
where the sheet used to just vanish. That is the guard working as designed on a surface that is
now a page, not a regression — but it IS an extra tap, and it is asserted in the walk so it cannot
change silently.

**Enforcement stays a browser walk.** `nav-smoke.mjs` gains a More section that proves the three
properties that motivated the change: opening it PUSHES (going forward after a Back returns to it),
Back POPS to the page you opened it from, and a link inside it leaves the bar mounted. The pure
decisions stay in `tabBarPolicy` / `moreNavPolicy` as unit tests.

**Deferred.** The employee persona's bar has no More entry and is untouched. Whether the desktop
sidebar should also collapse behind a single destination at intermediate widths is a separate
question this does not answer.
