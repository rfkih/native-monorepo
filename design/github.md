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
