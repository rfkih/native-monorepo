# 0075. A navigation and overlay contract for the console

- **Status:** Accepted
- **Date:** 2026-09-08
- **Deciders:** owner + Claude (frontend)
- **Related:** [ADR 0043](0043-native-android-till-app.md) (the Android shells whose hardware Back
  this must compose with), [ADR 0049](0049-business-and-employee-apps-outlet-terminal-auth.md),
  [ADR 0062](0062-web-build-version-gate.md); `frontend/console/scripts/nav-smoke.mjs` is the
  executable form of this document.

## Context
The owner's report was that the console "does not feel seamless — it jumps around". An audit found
that this was not one bug but the absence of a contract: nothing said what a back control, a
navigation, or an overlay is supposed to DO, so each screen answered differently and the answers
contradicted each other.

- **Back had four meanings.** Every back arrow was a `<Link>`, so pressing back PUSHED a new
  history entry. Four of them (`/menu`, `/inventory`, `/kitchen`, `/catalog`) pushed a fixed
  `/pos`, so an owner who opened the menu from the office sidebar was dropped into the cashier
  till; one of those carried an `a11y.backToDashboard` label while doing it. A correct helper
  (`guardedNavigateBack`) existed, was unit-tested, and was used in exactly one file out of two
  apps. Inside the Android shells this compounded: the guard parks a sentinel above every route,
  so an inflated stack let `confirmLeave`'s `go(-2)` land the user FORWARD of where they started.
- **No scroll management existed.** `window.scrollTo` appeared twice in the whole console.
  Navigating from a scrolled list opened the next page at the previous offset.
- **Chrome was not stable.** The Suspense boundary sat at the app root, so a lazy route chunk
  replaced the entire screen — sidebar included — with a skeleton whose content column was 100px
  narrower than the real one. The catch-all route sat inside the Shell layout route, so every
  unknown path mounted the back office for one commit before redirecting.
- **There was no overlay primitive.** Six near-identical `DialogOverlay` copies had already
  drifted; none of them managed focus, and all six put `onKeyDown` on a div with no `tabIndex`, so
  Escape never fired. Nothing anywhere animated out.

## Decision
We adopt six rules. New UI complies with them; existing UI is migrated as it is touched.

- **N1 — A back control POPS; it never PUSHES a destination.** Use `BackButton` (or `ScreenHeader`'s
  `backFallback`). The declared route is a FALLBACK for when there is nothing to pop — a deep link
  or a cold open on a sub-page — never the normal path. `backIntentFor` (backGuardProtocol) is the
  single decision, including the sentinel arithmetic. A breadcrumb is not a back control: it names
  a destination and may push.
- **N2 — One page, one chrome.** Chrome does not change without a deliberate route change.
- **N3 — Every modal goes through `components/ui/Dialog`.** Backdrop, z-level, Escape, hardware
  Back, scroll lock, focus handling and both animations are the primitive's, not the call site's.
- **N4 — PUSH lands at the top, POP restores the offset, REPLACE moves nothing**, and a URL with a
  hash is left to its anchor. The console owns `history.scrollRestoration`; see below.
- **N5 — State that changes the main content lives in the URL**, two-directionally, via `replace`.
- **N6 — A Suspense fallback replaces content, never chrome.**

Out of scope: the till's internal overlay stack keeps its own z-literals (it composes a stack of
its own); this ladder governs everything outside the POS.

## Consequences

**Scroll restoration is ours now.** `scrollRestoration = 'manual'` is set deliberately. The
console renders routes against a DEFERRED location (`TransitionedRoutes`) behind lazy chunks, so
when `popstate` fires the document still holds the previous page at the wrong height; the browser
restores against that, clamps, and never corrects — measured as 1194px where 600px was wanted. This
is not discarding behaviour that worked, it is taking over behaviour that did not. The cost: we
must re-apply a restore per frame until the destination has grown to fit (700ms ceiling), and yield
the moment the user touches the page.

**Enforcement is a browser walk, not a linter.** `scripts/nav-smoke.mjs` runs the contract against
a dev server (`VITE_AUTH_MODE=dev npm run dev`, then `node scripts/nav-smoke.mjs`). It proves
pop-vs-push by going FORWARD after a back press, and each section is isolated so one missing
control cannot mask the rest. Sections that cannot discriminate are removed rather than left to
pass for the wrong reason — there is deliberately no "PUSH lands at the top" check on a page the
browser would clamp to 0 anyway. The pure decisions (`backIntentFor`, `scrollActionFor`,
`navKindOf`, the scroll memory) are vitest unit tests.

**A real bug surfaced while writing the walk**, unrelated to the refactor: an overlay closing via
its UI parks a self-pop token and calls `history.back()`, and the token is spent by the first
handler to see the event — but by then the scheduling overlay has unmounted, and if it was the last
one open AND the route guard is inactive (any plain browser or PWA; the guard runs only inside the
Android shells) nobody was listening at all. The token survived to swallow the user's next genuine
Back press. The unwind now registers its own one-shot backstop.

**Deferred, deliberately.** (1) The 73 dialog call sites pass no `aria-label`, so the dialogs have
no accessible name — a pre-existing WCAG gap, not a regression, and 73 naming decisions is its own
piece of work. (2) The `z-50` literals outside the shared primitives are not swept.
