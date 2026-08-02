repo: rfkih/native-monorepo
branch: feat/foundation-restaurant-service
path: frontend/console

## Last sync

date: 2026-07-27T00:00:00Z

### Updated in this project

- Consolidated every design into a single `Native Console.dc.html`; removed the superseded files.
- Brand moved from green to a deep cyan (`#0B7F99` fills, `#095F73` text); green now means profit only.
- POS frames recoloured to cyan, keeping green for Sent / Ready / Paid statuses.
- Audit closure table added: 14 of 17 findings resolved in design, 3 need code.

## Sync history

### 2026-07-26T16:44:28Z

- POS redesign — two directions for an 834×1194 portrait tablet, plus phone and breakpoints.

### 2026-07-26T16:21:00Z

- Read the full `frontend/console` React app (70 source files); produced the 17-finding design review.

## Screen map

| Section of Native Console.dc.html | Built from |
|---|---|
| 1a Foundations — cyan | `src/index.css` (`@theme` ramp it replaces) |
| 2a Shell + dashboard | `src/app/Shell.tsx`, `src/features/dashboard/Dashboard.tsx` |
| 2b Income statement | `src/features/statements/IncomeStatement.tsx`, `parts.tsx` |
| 2c Balance sheet | `src/features/statements/BalanceSheet.tsx`, `parts.tsx` |
| 2d Team | `src/features/team/Team.tsx`, `src/components/ui/*` |
| 3a–3c, 4a–4c Point of sale | `src/features/pos/Pos.tsx`, `BillsTray.tsx`, `TableFloor.tsx`, `BillDetail.tsx`, `ModifierModal.tsx`, `PaymentModal.tsx` |
| 5a–5b Register | `src/features/signup/Signup.tsx` |
| 5c Landing hero | `src/features/landing/Landing.tsx`, `art.tsx` |
| 6a Audit closure | whole of `frontend/console/src` |

## Not yet designed

Organisation, Groups, Period close, Onboarding wizard, Menu management, Kitchen display.

## 2026-08-02 — POS redesign P4/P5: "precision terminal"

The POS across all three verticals moved onto the shared shell (`src/features/pos-shell/`,
ADR 0034). Design direction: the existing cyan system executed as a dedicated terminal —
one dark surface (the 56px ink-900 status band: identity, outlet, an always-visible
connection pill, ≤3 pinned actions, till-menu overflow) framing a bright work area; a
pinned **Walk-in** tab makes the ticket destination explicit; the bottom **ticket dock**
carries a destination pill (→ order switcher), the 20px-mono total, and honest verbs
(walk-in `Charge amount`; bill `Send n` fires the KOT directly, `Pay total` opens payment
directly — exact tendered pre-filled). Tiles are shadow-layered (no resting border); the
category rail marks active with a 3px brand bar; motion stays on the three verbs
(press/pop, 300ms surface slides, reveal). Verified via `scripts/pos-matrix.mjs`
(30-shot vertical × viewport × theme matrix incl. print-emulated receipt + KOT shots —
the print tripwire that caught the blank-receipt specificity bug).

| Surface | Files |
| --- | --- |
| Terminal chrome | `src/features/pos-shell/layout/PosStatusBar.tsx`, `TillMenuSheet.tsx` |
| Payment surface | `src/features/pos-shell/payment/*` (frame, breakdown, cash/digital/full-coverage views) |
| Restaurant | `src/features/pos/Pos.tsx` + `components/` (BillTabsBar walk-in tab, SummaryBar dock, BillSelectorOverlay switcher, CategoryRail, MenuTile) |
| Carwash/Barbershop | `src/features/servicepos/ServicePos.tsx` (shared chrome; 45dvh ticket cap on portrait) |

Still open (design): service requirement-chip dock + pinned Charge; restaurant ≥lg
right-rail ticket panel; Kitchen display (still undesigned).
