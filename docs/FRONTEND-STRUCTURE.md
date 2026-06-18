# FRONTEND-STRUCTURE.md — the console/app conventions

> Companion to **CODE-STRUCTURE.md** (backend). Read before touching `frontend/`. The frontends are
> the one place CLAUDE.md rule 9 lives in full force: **no hardcoded user-facing strings, all money /
> dates / numbers via locale-aware `Intl`.**

## Stack (pinned)
**Vite + React + TypeScript + Tailwind v4 + TanStack Query + react-i18next + Recharts** (lucide-react
icons, `motion` available). Node ≥ 22. One app per surface under `frontend/`:

```
frontend/
  console/          the per-company management console (this app)
  employee/         the employee PWA (later)
```

## Layout — feature-based (mirror the backend's package-by-feature)
```
src/
  app/            App (router) + Shell (chrome). Route-level code splitting (React.lazy).
  features/<x>/   one folder per feature: api.ts (typed calls + TanStack hooks) + components.
                  Today: onboarding/ (company wizard), dashboard/ (revenue + P&L).
  components/ui/  the design-system primitives (Button, Card, Field, Badge, Segmented, …).
  components/     cross-feature pieces (Wordmark, LanguageSwitcher).
  lib/            api client, money/period Intl helpers, queryClient, session, cn.
  i18n/           i18next init + locales/en.ts + locales/id.ts.
  index.css       the design system (@theme tokens) — the single source of visual truth.
```

## Hard conventions
1. **No hardcoded user-facing strings (rule 9).** Every label/message is a `t('…')` key. `en.ts` is the
   canonical shape; `id.ts` is `satisfies typeof en` so a missing key fails the build.
2. **Money is `{ minor units + ISO-4217 currency }`, never a float (rule 8).** Render via
   `lib/money` → `Intl.NumberFormat`. minor→major uses the ISO-4217 exponent (matching the backend's
   `libs/money`), NOT CLDR display digits (they differ for IDR). Numbers are tabular mono (`.tnum`).
3. **Dates/periods via `Intl`** with the locale derived from the active language (`localeOf`). Periods
   are `YYYY-MM` strings matching the finance API.
4. **Tenancy in headers, never the body (rule 5).** `lib/api` sends `X-Company-Id` / `X-Actor` (the
   gateway's stand-in; in prod the gateway injects them from the JWT). The console sends the company it
   is acting as (`lib/session`), never picks a tenant from a form.
5. **Settings live at creation.** Base currency + default language are set in the onboarding wizard and
   shown read-only thereafter — the dashboard never offers a currency/language toggle (matches the
   backend rule). A view-only **presentation-currency lens** on the dashboard is a separate convenience,
   clearly labelled and badged when the FX rate is provisional/stub.
6. **Server data via TanStack Query.** Query keys include the tenant + period (e.g.
   `['pnl', companyId, period, lens]`). Errors are RFC-7807 `ProblemDetail` (`lib/api` `ApiError`).
7. **Provisional figures are badged, never silent.** `usesIllustrativeRules` (placeholder
   statutory/payroll data) and `usesStubFx` (placeholder FX) each surface an amber i18n badge — money is
   never shown as verified when it isn't.

## Design system
The "ledger" aesthetic: warm paper + ink, hairline rules, a single confident **emerald**, **amber**
reserved for provisional/flagged, **rose** for negatives; Fraunces (display) + Instrument Sans (UI) +
JetBrains Mono (tabular money). All tokens are `@theme` CSS variables in `index.css` — change them
there, not per-component.

## Commands (run in `frontend/console`)
`npm install` · dev `npm run dev` (Vite proxies `/api/**` → the gateway at `localhost:8080`) ·
build `npm run build` (tsc strict + vite) · lint `npm run lint`. Live data needs the backend stack up
(`docker compose -f docker/compose.dev.yml up`) behind the gateway.
