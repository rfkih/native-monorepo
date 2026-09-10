# 0080. On the phone the three statements are one screen, and the chart is the period picker

- **Status:** Proposed
- **Date:** 2026-09-10
- **Deciders:** owner + Claude (frontend)
- **Related:** [ADR 0065](0065-gl-derived-dashboard-pnl.md) (the `/api/v1/pnl` contract the
  Income trend reuses — an empty month is a zero, not a gap), [ADR 0075](0075-navigation-and-overlay-contract.md)
  (Back pops: a sheet closes, then the report is left — tabs are never history entries),
  [ADR 0077](0077-neutral-ink-brand-replaces-deep-cyan.md) (the ink palette; green and red stay
  meaning, never identity), the Neraca plain-language redesign (v0.1.46: liquidity groups, netted
  equipment, the negative-asset allowlist, zero rows hidden on screen but printed) which this
  screen reuses rule for rule.
  Source design: `Native Laporan.dc.html` (Claude Design, 412×915 portrait).

## Context

`/statements/income`, `/statements/balance-sheet` and `/statements/cash-flow` had one layout —
desktop — which the phone merely compressed: a 28px title, three summary cards stacked, a
`‹ Sep 2026 ›` stepper, icon-only Print/Export, then the account tables inside cards. Nothing in
`features/statements/` knew it was on a phone. Reading a month meant scrolling past chrome; comparing
two months meant stepping one at a time with no sense of the year's shape; reaching the cash flow
from the income statement meant the sidebar, which on a phone is the More screen.

The mockup answers with one screen, **Laporan**, repeated four times: tabs (Income · Balance ·
Cash flow · Expenses), one answering figure, a twelve-month chart that is *also* the period control,
then the detail — supporting figures, banners, the balance-sheet equation, composition bars, the
ledger down to the last account, the zero-row toggle — a drill-down sheet and an export sheet.

## Decision

1. **Below 640px the three pages delegate to one screen.** Each page still owns its route, runs
   every hook it always ran, and only then returns `<Laporan tab=…/>`. The three routes stay; the
   Expenses tab is `/statements/income?tab=expense` — a pure derivation of the income statement,
   no endpoint. Tabs navigate with `replace`, so deep links, the accountant's home
   (`/statements/income`), the Reports tab and Back all keep meaning what they meant. A tab switch
   is a route change — a different lazy element, so the screen remounts — which is why the
   selected month and the chart type live in the URL (`?period=`, `?chart=`) and not in state:
   the month picked on Income is the month shown on Balance. The screen renders inside the Shell
   and draws no chrome of its own (an in-flow title row, like DashboardPhone) — the Shell's
   topbar is the one sticky header.
2. **The chart is the period picker.** Twelve trailing months up to *today*, fixed; tapping a
   column moves the whole report — hero, banners, ledger — to that month. The window never
   re-anchors on a tap: re-windowing would push the tapped month to the right edge and lose the
   way forward. The selected column is the only one in colour; the rest are `ink-200`.
3. **No trend endpoint.** Twelve columns are twelve calls, exactly as the dashboard already does it
   (`usePnlTrend`, `useQueries`), and the trend runs only for the active tab. Balance and Cash flow
   key each month *identically* to their single-period hook, so the month on screen shares its
   cache entry with its column; `staleTime` is five minutes because a closed month does not
   change. Income and Expenses draw their window from `/api/v1/pnl` — the Beranda's own trend,
   one cache — plus the one statement call for the month on screen.
4. **Every rule is reused, none re-derived.** Liquidity grouping, equipment netting, the
   negative-asset allowlist, zero-row splitting, the reconciliation banners, the equation and the
   account names all come from the modules the desktop pages already use (`balanceSheetView.ts`,
   `incomeDetail.ts`, `accountLabels.ts`, `parts.tsx`). What the desktop pages did inline —
   the display mapping of the Neraca, the three CSV builders, the drawer body — moved into pure,
   tested modules (`displayBalanceSheet`, `statementsCsv.ts`, `IncomeDetailBody`) that the desktop
   pages now consume too, so the phone and the desktop cannot disagree on a row.
5. **The chart obeys the dataviz specs.** Columns ≤24px in a 44px slot, 4px rounded data end and
   square at the baseline, 2px line, ≥8px selected marker with a surface ring, hairline solid zero
   baseline, each column a full-height hit target with a title tooltip that enhances and never
   gates (the selected value is the hero; every value is in the ledger). Labels wear text tokens;
   only the marks wear the series colour, and colour is meaning: a signed series (net, cash) is
   green/red by sign, a magnitude (net worth, expense) is ink.
6. **Refetch keeps the frame.** Moving months keeps the previous month on screen, dimmed, until
   the new one lands (`keepPreviousData`); an empty month keeps the chart and says so under it,
   with the next action ("tap another month") spelled out.
7. **The sheets are the one Dialog primitive** (`DialogOverlay`, ADR 0075 N3 — a bottom sheet on
   phone). Print fires only after the export sheet has left the DOM and released its scroll lock;
   the tabs, the chart and the actions are `print:hidden`, so the printout is the statement.

## Consequences

- Desktop renders as before: the CSV export is now the shared builder (a test locks the column
  contract and the padded total rows), the Neraca consumes `displayBalanceSheet`, and the income
  drawer renders the shared `IncomeDetailBody` at its own metrics (`size="md"`). One deliberate
  change there: revenue bars are ink, not green — green is profit and nothing else (ADR 0077).
- The phone chart is geometry with tests (`periodChartMath.ts`) — scale, column anchor, the track
  with its own end inset, centring — so the component is presentation only.
- Cost: a first visit to a tab is twelve small GETs. Accepted as the mockup's budget; a server
  trend endpoint remains the obvious optimisation if it ever shows on the gateway.
