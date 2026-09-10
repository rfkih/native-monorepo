# 0082. The phone home reads today, not the month

- **Status:** Proposed
- **Date:** 2026-09-11
- **Deciders:** owner + Claude (frontend + restaurant-service)
- **Related:** [ADR 0065](0065-gl-derived-dashboard-pnl.md) (the monthly Beranda this supersedes on
  the phone — the desktop keeps it), [ADR 0071](0071-analytics-star-schema.md) AR-1 (which words
  may come from where), [ADR 0074](0074-sales-leak-detection.md) (the outlet-local day and the
  daily SQL shape reused), [ADR 0067](0067-perpetual-inventory-accounting.md) (the sale-time COGS
  fold the margin reads), [ADR 0080](0080-the-phone-reports-chart-is-the-period-picker.md) (where
  the month went), [ADR 0081](0081-the-inventory-reads-in-days-not-quantities.md) (the low-stock
  rule reused), [ADR 0075](0075-navigation-and-overlay-contract.md) N2 (one page, one chrome),
  [ADR 0077](0077-neutral-ink-brand-replaces-deep-cyan.md) (the ink palette). Source design:
  `Native Console Android.dc.html`, screen "Beranda manajer" (Claude Design, 390×844).

## Context

The phone home (`dashboard/DashboardPhone.tsx`, Aug 7) was an honest re-fit of the design as it
stood: the month's GL net on an inverted card, a period stepper, the month by outlet, two quick
tiles. Its header comment said why it stopped there — *"no invented 'today' numbers — the design's
daily hero waits for a daily-sales endpoint"*. The design has since moved on and now leads with
today: the company's sales so far, against the same weekday last week, over a seven-day strip;
four figures about the day; what needs a decision; today by outlet; the best sellers; four doors.

Nothing served that. finance-service speaks in `YYYY-MM` only; restaurant-service had per-session
register sums (the X/Z report — a session window, not a calendar day, and absent for an unclosed
day) and a 200-row sales list (which silently truncates a busy day). `sale.cogs_minor` (V44) was
written on every costed sale and read by nothing. The analytics-service of ADR 0071 that would one
day own trends is unshipped past its event door. Meanwhile the month had found a better home: the
Laporan tab (ADR 0080) already shows the twelve-month trend and the three statements, so the phone
home was the second screen saying the same monthly number.

## Decision

**One new read, and the home reads it.** restaurant-service gains
`GET /api/v1/sales/daily?businessId&from&to` — one row per OUTLET-LOCAL calendar day in the
inclusive window that had a tendered sale or a refund: `netSalesMinor` (total − refunds),
`transactionCount`, `cogsMinor` with `costedTransactionCount`, `currency`,
`usesIllustrativeRules`. Its rules are the register summary's, restated per day:

- **The day is `OutletZone`'s** (Asia/Jakarta, ADR 0074): `occurred_at` and `refunded_at` are
  shifted before the `::date` cast, the same attribution as the register's business date and the
  V47 ledger, so today's figure and today's stock usage agree on what "today" is.
- **The universe is tendered sales** (`tender_type IS NOT NULL`) — exactly
  `RegisterSessionRepository#summarizeSales`'s population, so a day here nets what its Z-report
  nets. A gift-card-settled sale (null tender) is outside it in both places.
- **Refunds net against the day they were refunded on**, from the append-only `payment_refund`
  ledger (mirrors the closed-history query), joined FULL OUTER so a day that only refunded shows
  negative instead of vanishing. A refunded sale still counts as rung.
- **COGS is summed as written**, never re-derived from today's recipe; `SUM` of an all-NULL day is
  NULL and stays so, and the costed count travels with it so a partially costed day is never
  presented as fully costed.
- The route is already under the gateway's `/api/v1/sales/**` POS_ROLES rule; the writer enforces
  the same `OutletAccessGuard` as the history read (owner/manager pass, everyone else needs the
  outlet). The window is capped at 92 days; an inverted window is a 400, not an empty list.

**The home folds outlets client-side.** restaurant speaks per outlet; the home speaks for the
company, so `todayApi.ts` fans one call per outlet (`useQueries`, keyed like the POS hooks for the
same resource so the till and the home share cache entries) and `lib/todayView.ts` — pure, tested —
folds the rows into company days and reads each figure off them: the strip is seven days ending
today scaled to the tallest; the comparison is the same weekday one week back (the only comparison
that survives a weekend rhythm) and is simply absent when that day has nothing to compare against;
average bill is net ÷ count; gross margin is `(net − cogs) / net` only when at least one sale was
costed, marked partial when not all were. Open bills and the best sellers reuse the POS's
`/bills?status=OPEN` and `/orders/item-sales` per outlet — best sellers merge by their sold-time
name because the same dish has a different id in every outlet.

**Which words come from where** (ADR 0071 AR-1 restated). *Omzet hari ini* is restaurant's gross
sales net of refunds — an operations figure, not a GL word, so it is read from restaurant and does
not pretend to be "Pendapatan". *Laba bersih* and the month stay finance's and live in Laporan; the
home's fourth door goes there. The margin is the sale-time COGS snapshot and is labelled *margin
kotor*, never HPP-as-posted.

**Tasks are the same doors More offers, gated the same way.** Claims waiting
(`/expense-claims?status=SUBMITTED`, HR), low stock (the ADR 0081 class ladder over the active
outlet's catalog: `zero` + `low`, names first), and the current period still open (absent from
`/closes`, FINANCE) — the current one, because that is the only period `PeriodClose` offers to
close; the design's "Tutup buku Agustus" in September names a close the console cannot make. A
row renders only with something in it; the section only with a row.

**A books-only login keeps the month.** Every today read is a POS_ROLES route, so an office login
without POS access (an accountant alone) would 403 on all of them. `DashboardPhone` checks
`canPos(auth.roles)` — the outlet token's own roles, as the More page does — and renders the
previous monthly composition (`DashboardPhoneMonthly.tsx`, kept byte-for-byte) for it.

**No chrome of its own** (ADR 0075 N2): the header row — monogram, company, "Jumat, 11 Sep · 3
gerai", ⋮ to `/more` — is in-flow; the Shell's topbar stays the one sticky header.

## Consequences

**The home is live and honest.** Every figure names a source that exists; none is monthly data
relabelled as today. The first-sale prompt needs a SUCCESSFUL, all-zero `/pnl` for this month and
last on top of an empty week — a finance outage or a week's holiday over a month start is not
"never sold" — and a failed bills read shows "—", never "none open". The daily rows are cached
30 s; open bills 10 s; item sales 60 s. An outlet whose call fails leaves the figure partial and
the screen says so with a retry; only when every outlet fails does the hero give way to the error
diagnostics.

**Requests per visit** scale with outlets: three per outlet (days, bills, items) plus outlets,
two `/pnl` for the fresh-books check, and the gated task reads. For a three-outlet company that is a
dozen small reads, all cached, none blocking another.

**One zone, still.** The day is fixed Asia/Jakarta on both sides (`OutletZone` on the server,
`usageDayKey` on the client) — the same v1 simplification ADR 0074 accepted. When a per-outlet
zone lands, the daily query, the client day keys and the item-sales bounds move together.

**The margin is a sale-time snapshot.** It reflects the moving-average cost at ring time (ADR
0067), not a re-costing, and it says "HPP 12/40 transaksi" when only part of the day carried a
recipe. A day with no costed sale shows "—", never 100%.

**`useIngredients` gained an `enabled` flag** (default true) so the low-stock task can be gated
by role without a stray 403.

**Open.** No per-outlet zone; the "open bills" tile cannot say how long a bill has been open
(`BillSummaryResponse` carries no opened-at, so the sub-line is the value on the floor instead of
the design's "3 lewat dari 1 jam"); no hourly shape of the day (ADR 0071 P2+).
