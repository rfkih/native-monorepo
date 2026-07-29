# DEVLOG — history, key decisions, current status

> **For an AI agent:** this is the durable record of *what was built, why, and where we are* — the
> decisions especially (the code shows the *what*; this shows the *why*, which you can't re-derive).
> Keep it current: when you finish a milestone or make a design decision, add a dated line. The live
> task list is ephemeral; this file is the memory. Update the **Current status** section as you go.

## Current status (update me)
**Backend: complete, hardened, and proven end-to-end.** All of Phase 0–3 backend is built, every
milestone team-built → adversarially reviewed (code + security + domain-correctness) → fix-rounds →
full build green → committed. The validation slice runs **live** (sale → outbox → Debezium → Kafka →
finance → consolidated revenue = verified against real infra, not just Testcontainers). CI + Kustomize
deploy authored (unverified vs a real cluster). The whole codebase is **package-by-feature with
controller/service/repository/domain/dto/messaging layer sub-packages**, ArchUnit-enforced.

**Not done (hard gates — need a human/SME/infra, do NOT invent):**
- Frontends — **console slice live** (`frontend/console`: onboarding wizard, consolidated revenue/P&L
  dashboard, and a **cashier POS**, en/id, Intl money). Now behind **real Keycloak OIDC login** with
  **role-gated surfaces** (cashier → POS only; owner/manager → dashboard + POS), proven on the running
  authenticated stack. **The org-tree, group-consolidation, and period-close console pages are now
  built** (`frontend/console`: `/org`, `/groups`, `/close` — owner/manager-gated, en/id, Intl money,
  illustrative badges) over new RLS-scoped read-endpoints (org-service GET /api/v1/org-units, GET
  /api/v1/consolidation-groups[/{id}/members]; finance GET /api/v1/closes; gateway DASHBOARD_ROLES
  routes; real-DB two-tenant RLS isolation tests guard the RLS-only reads). Remaining: the employee
  PWA — design decisions, never autonomous.
- **Official DJP/BPJS statutory figures** — payroll ships `ILLUSTRATIVE_PLACEHOLDER` data (provenance
  column + loud seed + runtime flag); a tax SME must seed real effective-dated figures.
- **Full IAS-21 multi-currency consolidation** (CTA/OCI, historical-rate equity, opening-balance
  roll-forward) — ships a FLAGGED-SIMPLIFIED translation; needs an accounting SME.
- Live infra (a real registry/cluster/secrets for the deploy; a real notification transport).

**Open follow-ups (tracked):** notification real provider (needs a transport choice); payroll
expected-source registry (needs a rule); **POS indirect-tax + accounting** —
the PB1-vs-PPN identity, rates, service-charge-revenue-vs-tip treatment, and GL account mappings ship
`ILLUSTRATIVE_PLACEHOLDER` and need a tax/accounting SME (ADR 0006); **real QRIS/card PSP adapter +
settlement webhook** (ADR 0007, needs a provider choice); **own-sales commission is single-currency**
— `MetricPublished` carries a bare minor-units `value` with no currency, so commission is denominated
in the payroll base currency; correct only while sales are in the base currency. When multi-currency
lands, add an optional `currency` to the metric schema and reject a metric whose currency ≠ the
run/package currency (code-review W1; guarded today by the single-currency slice, commented at the
emit + resolve sites). (DONE: the P3d deferred operational items —
`member_group_index` backfill, the within-company concurrent-close lock, the within-close MVC tests;
the finance-expansion posting-currency robustness guard; and the org-tree move/deactivate semantics —
cascade-deactivate + reactivation.)

## Key design decisions (the why)
- **Package root `id.co.nativeapp`** — `id.co` reverse-domain; `nativeapp` because `native` is a Java
  reserved word (illegal package segment).
- **Layered + package-by-feature, ArchUnit-enforced** (not hexagonal) — controller→service→repository→
  domain, grouped by capability; later refactored so each feature has explicit
  `controller/service/repository/domain/dto/messaging` sub-packages (user preference for readability).
  The gateway is the documented carve-out (reactive edge, not aggregate-bearing).
- **The `*Writer` pattern** — every `@Transactional` write is its own `@Component` (`*Writer`), so it is
  invoked through the Spring proxy: a self-invocation would bypass the tx advice AND the
  `RlsAutoApplyAspect` that sets the tenant GUC. Load-bearing for RLS. Services orchestrate, Writers
  transact, Readers query.
- **RLS is enforced, not assumed** — every service connects as its own non-superuser role; tables use
  `ENABLE`+`FORCE ROW LEVEL SECURITY`; the tenant is a Postgres GUC (`app.current_tenant`) set per
  transaction via a scoped value (not ThreadLocal). Tested as the non-superuser `app_user`.
- **Group consolidation cross-tenant model (P3d)** — a group is a SECOND RLS scope: a new
  `app.current_group` GUC + a CONJUNCTION policy `group_id = app.current_group AND company_id =
  app.current_tenant` on the group tables. Members PUBLISH their trial balances (TrialBalancePublished),
  finance never cross-tenant-reads. Adversarially verified bypass-free. **Decision:** single-reporting-
  currency consolidation is fully correct; multi-currency is FLAGGED-SIMPLIFIED (balance-check gate +
  residual to a flagged CTA reserve + `uses_simplified_translation_policy`); full IAS-21 deferred to an
  SME. (Memory: `p3d-consolidation-scope`.)
- **CDC wire = base64'd Avro bytes** — the outbox payload is `bytea` (raw Avro); Debezium decodes it as a
  `ByteBuffer` that `ByteArrayConverter` rejects, so the connector base64's it (`binary.handling.mode`)
  and the consumer's `libs/events Base64ByteArrayDeserializer` decodes it back. AvroSerde + the "no
  Confluent serde" design are unchanged. (See RUNBOOK gotcha #3.)
- **Flagged-illustrative domain data** — anywhere real domain law is needed but absent (statutory
  payroll figures, FX rates, consolidation policy), the MACHINERY is real but the DATA is loudly flagged
  (provenance enum + loud seed comment + a runtime flag on the run/event) so it can never be mistaken
  for verified production values. Never invent tax/accounting law as production values.

## Milestone history (newest first; commit refs are illustrative anchors)
- **Cash-flow statement + Budgets — Phase 5 of the Odoo accounting-parity program (2026-07-29, ADR
  0019)** — the two remaining reporting/planning pieces. **Neither posts to the GL** (cash flow is
  GL-derived; budgets compare against GL actuals), so no money-critical journal posting. **(A) Cash Flow
  Statement** (indirect, in `statements/`, NO migration / NO new gateway route — extends the
  already-routed `/api/v1/statements/**`): `glTrialBalance(period)` IS the per-account net movement, so
  `m = debit − credit`; cash & equivalents resolved from the `BANK`/`CASH_CLEARING`/`QRIS_CLEARING`/
  `CARD_CLEARING` roles; net income + working-capital adjustments (`credit − debit` per non-cash BS
  account — asset ↑ uses cash, liability ↑ provides it) classified operating/investing/financing;
  `netChangeInCash` **reconciles exactly** to the cash-account movement by double-entry (the reader
  asserts it, like BalanceSheetReader's balance check). `CashFlowReader` + `CashFlowResponse` +
  `/api/v1/statements/cash-flow`. **(B) Budgets** (`finance/budget/`, V33 `budget`+`budget_line`
  parent/child, FORCE RLS, per-month): a named monthly set of `account_code → planned amount_minor`
  (FK to chart_of_account, `amount ≥ 0`); the **budget-vs-actual** report joins each line's plan against
  the account's type-normal GL actual for the period → variance = actual − planned (no new "actual"
  store). `BudgetWriter` (create/delete, validates the account exists → 400), `BudgetReader`,
  `BudgetActualReader`, `AccountCatalogReader` (the COA picker), `/api/v1/budgets/**` (new gateway
  `budgetsRoute`, DASHBOARD_ROLES). Console: `statements/CashFlow.tsx` (reports grant) + `features/budget/`
  (list + create dialog with account picker + the variance report, plain canDashboard), en/id.
  **Verified: 462 finance + 66 gateway tests green** (CashFlowReaderTest — net income + working-capital
  adjustments + the exact reconciliation + an unbalanced set rejected; StatementsControllerTest cash-flow
  200/204/400; BudgetControllerTest 201/200/404/400/409/422; BudgetTenancyIsolationTest E2E — variance vs
  seeded AR/AP actuals, RLS-isolated, unknown-account 400, delete) + console `npm run build`. Built in
  the no-space worktree `C:\native-ar-build`. **Code-review PASS** (tenancy/RLS, fresh context) — budget
  RLS clean, cash-flow reconciliation a provable identity; one warning fixed (budget-vs-actual now
  guards the budget currency against the GL currency → 422, no cross-currency variance) + the account
  picker restricted to P&L accounts. **SME gate:** the cash-flow activity classification
  (current-vs-non-current, operating-vs-financing) is illustrative — everything is operating today (no
  fixed assets / financing). Program → **~85% Odoo accounting: (1) AR ✓ (2) AP ✓ (3) Bank ✓ (4) Tax ✓
  (5) Cash-flow & budgets ✓**; remaining (6) Fixed assets & deferrals. Deferred: multi-month/annual
  budgets; budget line editing (create+delete only); direct-method cash flow; comparative columns.
- **Tax / PPN — Phase 4 of the Odoo accounting-parity program (2026-07-29, ADR 0017)** — AR accrues
  output VAT to `2200` and AP input VAT to `1300` (illustrative 11%), but nothing turned those into a
  **tax return**. This adds the PPN (Indonesian VAT) pillar: a GL-derived **VAT report** (output =
  credit-net of `2200`, input = debit-net of `1300`, **net = output − input** → PAYABLE/CREDITABLE),
  an idempotent **File return** that posts the period-end netting entry and seals the period, and a
  **Settle** posting when the net is paid. New `finance/tax/` feature: V31 (`tax_filing` seal,
  UNIQUE(company,period,tax_type), FORCE RLS) + V32 (COA `2300 VAT Payable` + `1310 VAT Credit
  Carryforward` + role maps; no posting_template). `VatReturnReader` wraps `GlTrialBalanceReader`
  (inheriting its balance + single-currency asserts) and resolves VAT_OUTPUT/VAT_INPUT via
  `RoleAccountResolver`. Filing posts an **ad-hoc balanced netting entry into the RETURN period**
  (built directly in `TaxFilingWriter`, no posting_template/EventKind — the Bank approach, because the
  net leg flips side): `Dr 2200 (output) / Cr 1300 (input) / Cr 2300 (net payable)` or `Dr 1310 (net
  creditable)`, zero legs omitted; once filed the report reads the sealed `tax_filing` snapshot (the
  netting cleared the period's 2200/1300). Idempotent via an advisory lock + `findByPeriodAndTaxType`
  probe + UNIQUE `source_event_id`=filing id (re-file → no-op). **Settle** posts `Dr 2300 / Cr
  CASH_CLEARING (1900)` (routes the payment through the same clearing every cash movement uses), a
  one-shot FILED→SETTLED transition (status guard + UNIQUE source_event_id, no Idempotency-Key —
  bank rationale); CREDITABLE/zero-net is terminal at FILED. Single-base-currency guard on every post
  (→422). **e-Faktur** = a JSON endpoint of the period's output tax invoices → the console renders a
  CSV download (real DJP API deferred). New `AccountRole.VAT_PAYABLE`/`VAT_CREDIT_CARRYFORWARD`.
  Endpoints `/api/v1/tax/**` (DASHBOARD_ROLES). Console `features/tax/` (VAT report KPIs + File/Settle
  + e-Faktur export + filing history, en/id). Balance sheet gains `2300`/`1310`; the period's
  `2200`/`1300` draw to zero on filing. **Verified: 442 finance + 64 gateway tests green** (incl.
  TaxFilingPostingTest — the netting legs for payable/creditable/nil + settlement + the state machine;
  TaxControllerTest 200/201/400/404/409/422; TaxFilingTenancyIsolationTest E2E: file → 2200/1300
  cleared + 2300=660,000, settle → 2300=0 + 1900 credited, re-file idempotent 200, RLS-isolated;
  **TaxFilingConcurrencyTest** — two-thread races prove file()/settle() are each exactly-once under
  contention). **Code-review PASS** (money/tenancy, fresh context); two warnings fixed — idempotent
  re-file returns 200 not 201 (ENGINEERING-STANDARDS §1.1 + the close sibling), and the mandated §3.2
  concurrency proof added. Built in the no-space worktree `C:\native-ar-build`. **Most SME-gated
  phase** — rate, carryforward policy, PKP
  status, e-Faktur schema, filing deadlines all flagged illustrative (ADR 0017). Program → **~80% Odoo
  accounting: (1) AR ✓ (2) AP ✓ (3) Bank ✓ (4) Tax ✓**, (5) Cash-flow & budgets, (6) Fixed assets &
  deferrals. Deferred: amended returns / late postings to a sealed period; net-void periods; PPh +
  other tax types; the real DJP e-Faktur integration.
- **Bank & Reconciliation — Phase 3 of the Odoo accounting-parity program (2026-07-29, ADR 0016)** —
  AR receipts, AP payments, AND POS sales all post to CASH_CLEARING (`1900`) = cash in transit; this
  adds real **bank accounts** and a **reconciliation** flow that settles that clearing balance against
  bank statement lines (non-invasive — AR/AP/POS untouched). New `finance/bank/` feature: `bank_account`
  + `bank_statement_line` (signed `amount_minor`), V29 (tables) + V30 (COA `1000 Bank`/`4100 Interest
  Income`/`5400 Bank Charges` + role maps). **Reconcile-by-category** (the auto/line-item matching
  engine is deferred): reconciling a line posts an **ad-hoc balanced 2-line JournalEntry** built
  directly in `ReconciliationWriter` via `RoleAccountResolver` (no posting_template, no new EventKind):
  deposit/CLEARING → Dr BANK(1000) / Cr CASH_CLEARING(1900) [the sweep]; withdrawal/CLEARING → the
  reverse; withdrawal/BANK_FEE → Dr 5400 / Cr 1000; deposit/INTEREST → Dr 1000 / Cr 4100. Category is
  gated by direction (INTEREST only on a deposit, BANK_FEE only on a withdrawal → 400). One shared
  `BANK` control account (1000) — per-account balances in the sub-ledger, mirroring AR 1200 / AP 2000.
  Idempotent via the UNRECONCILED→RECONCILED status guard + UNIQUE `source_event_id`=lineId (re-reconcile
  → 409; no Idempotency-Key needed — it's a state transition, not a payment). Single-base-currency guard
  on the post (→422); DTO-only controllers; Location/​@Pattern/​LIMIT; all tables FORCE RLS. The
  reconciliation report = per-account bank balance (Σ reconciled lines) + the CASH_CLEARING GL balance
  (`SUM(debit−credit)` on 1900 = cash-in-transit awaiting sweep) + unreconciled lines. Endpoints
  `/api/v1/bank-accounts/**` + `/api/v1/bank/**` (DASHBOARD_ROLES). Balance sheet gains `1000 Bank`;
  `1900 CASH_CLEARING` DRAWS DOWN as lines reconcile (residual = true cash-in-transit); income statement
  gains 4100/5400. Console `features/bank/` (BankAccounts + a reconcile workspace with import + per-line
  reconcile + the report KPIs). **Verified: 417 finance + 62 gateway tests green** (incl. ReconcilePostingTest
  the 4 direction×category legs + BankTenancyIsolationTest E2E: +5,000,000 deposit swept + −25,000 fee →
  bank 4,975,000, clearing −5,000,000, RLS-isolated). Built in the no-space worktree `C:\native-ar-build`.
  Program → ~70% Odoo accounting: **(1) AR ✓ (2) AP ✓ (3) Bank & reconciliation ✓**, (4) Tax/e-invoicing,
  (5) Cash-flow & budgets, (6) Fixed assets & deferrals. Deferred: the line-item matching engine; CSV/
  bank-feed import; per-account GL accounts; multi-ccy bank accounts.
- **Accounts Payable — Phase 2 of the Odoo accounting-parity program (2026-07-29, ADR 0015)** — the
  vendor-facing MIRROR of AR: vendors, bills (draft → **posted** → (partially) paid | void),
  bill-payments, and an AP aging report, in **finance-service** (`ap/` feature), posting to the
  double-entry GL in-transaction via the existing `buildEntryFromBreakdown` (no new posting code; V28
  adds a `NET` amount_basis). **The GL sides are the CONTRA of AR** (a bill is a liability + expense,
  not an asset + revenue): **post** Dr `EXPENSE`(net=5000) / Dr `VAT_INPUT`(tax=1300, a recoverable
  ASSET) / Cr `AP`(total=2000); **payment** Dr AP / Cr cash-clearing; **void** the contra. New COA
  `2000 AP`(LIABILITY) + `1300 VAT Input`(ASSET); new `AccountRole.AP`/`VAT_INPUT` +
  `EventKind.BILL_POSTED`/`BILL_PAYMENT_MADE`/`BILL_VOID`. Single base currency; input PPN 11% is
  ILLUSTRATIVE (SME-gated); bill net posts to a single expense account (per-line cost centres
  deferred). Migrations **V27** (AP tables) + **V28** (GL config). **Every Phase-1 review fix baked in
  from line 1** (AR needed two rounds; AP got them in one): payment `Idempotency-Key` **required** +
  UNIQUE scoped to `(company, bill, key)` in V27; single-base-currency guard on **post + payment +
  void** (→422); aging mixed-ccy guard; DTO-only controllers, `Location` on 201s, status `@Pattern`,
  `LIMIT 500` on lists. AP flows into the balance sheet (2000 AP liability + 1300 VAT-input asset) +
  income statement (5000 expense) automatically. **Path collision resolved:** `/api/v1/bills` was
  already the restaurant "open bills" POS route, so AP bills are namespaced **`/api/v1/ap/bills`**
  (gateway `/api/v1/ap/**`; vendors at `/api/v1/vendors`); the AP `AgingController` was renamed
  `ApAgingController` to avoid a Spring bean-name clash with AR's. Console `features/ap/`
  (Vendors/BillsList/BillDetail/NewBill/ApAging) mirrors `features/ar/` (idempotency-key sent
  per-submit, overpay validation, aging invalidation carried forward). **Verified: 396 finance +
  62 gateway tests green** (incl. `ApTenancyIsolationTest` end-to-end RLS + `ApWriterIntegrationTest`
  idempotency+currency, mirroring the AR suite) + console `npm run build` green. Built in the no-space
  worktree `C:\native-ar-build` (space in `C:\Project 2` breaks Gradle), synced back. Program →
  ~100% Odoo accounting: **(1) AR ✓ (2) AP ✓**, (3) Bank & reconciliation, (4) Tax/e-invoicing,
  (5) Cash-flow & budgets, (6) Fixed assets & deferrals. Residual (both AR+AP): the sale/expense
  currency guard reads `consolidated_pnl` while AR/AP read `journal_entry` — a unified guard across
  all producers stays a tracked follow-up (unreachable while base ccy immutable+single).
- **Accounts Receivable — Phase 1 of the Odoo accounting-parity program (2026-07-28, ADR 0014)** —
  the first transactional AR layer + the first customer/party dimension in Native, all in
  **finance-service** (`ar/` feature). Customers, invoices (draft → issue → (partially) paid | void),
  payments/receipts, and an AR aging report. Invoices post to the existing double-entry GL **in the
  same transaction** as the sub-ledger write (no cross-service sync) via a new generic
  `JournalPostingService.buildEntryFromBreakdown` (an `amount_basis → Money` map reusing the
  `GROSS`/`GROSS_REVENUE`/`TAX` vocabulary — the SALE path untouched): **issue** Dr AR (1200) / Cr
  revenue (4000, net) / Cr output VAT (2200, tax, zero-omitted when non-taxable); **payment** Dr
  cash-clearing / Cr AR; **void** the contra. New `AccountRole.AR`/`VAT_OUTPUT` (already anticipated
  in the enum javadoc) + `EventKind.INVOICE_ISSUED`/`PAYMENT_RECEIVED`/`INVOICE_VOID`; illustrative
  COA/roles/templates seeded in **V25** (V24 = the four Auditable + FORCE-RLS AR tables). AR flows
  into the GL-derived **income statement + balance sheet** automatically (1200 AR = ASSET); it does
  NOT feed the dimensional POS `/pnl` dashboard (deliberate — the GL statements are authoritative).
  Reads are native-query projections (RLS-scoped, no `WHERE company_id`); aging buckets outstanding
  invoices by days-overdue in the reader. Gateway routes `/api/v1/customers|invoices|ar/**`
  (DASHBOARD_ROLES). Decisions (ADR 0014): AR is finance-local; customer is finance-local; sub-ledger
  drives aging (the GL journal has no counterparty dimension); **single-currency** (base ccy);
  **output tax flagged-illustrative** (PPN 11% placeholder, `uses_illustrative_rules` badged
  "Estimated", SME-gated like POS); **events deferred to Phase 1b** (no mail transport yet, so no new
  event added to the catalog). Invoice number = per-tenant `INV-NNNNN` via a `pg_advisory_xact_lock` +
  RLS-scoped COUNT (UNIQUE backstop). **Verified: the whole finance suite (367 tests) green**,
  including `ArTenancyIsolationTest` — an end-to-end Testcontainers test that drives create → issue
  (11% VAT: 1,000,000 → 1,110,000) → part-pay (300,000, outstanding 810,000) → aging against real
  PostgreSQL as the unprivileged `app_user` and proves cross-tenant RLS invisibility. ArchUnit
  layering + web-slice contract tests (201/400/404/409 RFC-7807) + gateway build green. **Deliberate
  Phase-1 exclusions** (later sub-steps): multi-currency invoices, credit notes, recurring invoices,
  PDF/email delivery, dunning, fractional line quantities. **Console AR feature built** (Customers /
  Invoices list+detail / New-invoice / Aging; additive to the console over the page-grants WIP, npm
  build green). **Two adversarial code-review rounds** (backend + frontend) landed fixes: payment
  idempotency (`Idempotency-Key` required + scoped per-invoice, V26 unique index; console sends a
  fresh key per submit); the single-base-currency guard on issue/void/payment (M1/W-1) → 422; aging
  mixed-currency guard; overpay client-validation + aging-cache invalidation. **Residual follow-ups
  (tracked):** the sale/expense currency guard reads `consolidated_pnl` while AR reads `journal_entry`
  — a unified single-currency-GL guard across ALL producers is deferred (defense-in-depth; unreachable
  in a correct single-currency tenant, base ccy immutable); AR list/aging pagination envelope (interim
  `LIMIT 500` on the two lists; aging still aggregates in-memory). Program roadmap → ~100% Odoo
  accounting: **(1) AR ✓**, (2) AP, (3) Bank & reconciliation, (4) Tax/e-invoicing, (5) Cash-flow &
  budgets, (6) Fixed assets & deferrals.
- **Employee logins + self-service /me + page grants + own-sales commission (2026-07-28)** — HR
  employees became loggable-in users with a dashboard of their own and a commission on the sales they
  ring. Six phases (A–F). **A/B — logins:** reused the org-service invite flow to create a Keycloak
  login for an employee (temp password shown once, forced change), optionally POS-capable via a
  checkbox (roles `[employee]` or `[employee, cashier]`; invite gained a `roles: string[]`, assigned
  sequentially). A new `employee` realm role threads through gateway `BUSINESS_ROLES`, org
  `ALLOWED_ROLES`, and the console. employee V7 = a nullable `user_id VARCHAR(64)` + partial-unique
  index; `POST/DELETE /api/v1/employees/{id}/login-link` sets/clears it. **The Keycloak `sub` is the
  universal join key** — gateway `X-Actor` = `jwt.getSubject()` = `TenantContext.actor()` =
  `sale.created_by` = `user_outlet_assignment.user_id` = `employee.user_id`; the console OIDC auth had
  to be taught to RETAIN `sub` (it defaulted to `preferred_username`). **C — /me:** gateway routes
  `/api/v1/me/**` (ME_ROLES incl. employee); employee-service `me` feature resolves the caller
  EXCLUSIVELY from actor→V7 link (never a request param, so a caller reads only their OWN rows). NIK/
  bank stay MASKED even to the person; only payslip AMOUNTS decrypt — this is the FIRST caller of the
  long-dormant `findPayslipAuthorized`. Console `/me` full-screen dashboard. **D — page grants (ADR
  0013):** org V8 `user_page_grant`, subtractive UI-level gating (`GET /users/me/pages` →
  `{mode, pageKeys}`; `GET|PUT /users/{id}/pages`). **Decision: grants NARROW the console; roles
  remain the API authz boundary** (no event — no consumer; staleness is a fetch away, not baked into a
  JWT). **E — commission = X% of the employee's OWN sales.** Three sub-decisions locked with the user:
  own-sales % (not team/pool), REAL payslip amounts on /me, POS via optional checkbox. **The
  load-bearing correctness fix:** the `MetricPublished` consumer projection was last-write-wins
  (`applyValue` REPLACED the natural-key row) — wrong for any per-unit-of-activity producer; carwash
  already undercounted same-day washes and a per-sale feed would collapse a day to its last ticket.
  Changed to delta-ACCUMULATE (`applyDelta`, `value += delta`), safe under the event-UUID idempotency
  guard. restaurant became the **second `MetricPublished` producer** (no schema change — the shared
  avsc already lists the `employee` grain): every sale emits `sales_amount`@`employee`, subject = the
  cashier's sub, in the SaleRecorded transaction, at both `SaleWriter` choke points. The engine gained
  `PERCENT_OF_METRIC` (reusing `earning_rule`'s existing `percent_basis_points` + `metric_key` columns
  — **no migration**): the run sums the employee's own-sub metric rows for the month and applies the
  rate via `Money.applyBasisPoints`, reusing the single-period-grain guard (mixed grain → throw, never
  double-count). Config API `GET/POST/DELETE .../compensation/{pkgId}/commission` (non-PII bp echoed;
  open-duplicate → 409). `GET /api/v1/me/sales` previews rate×sales (labelled a preview — the payslip
  is authoritative; currency from the open package, amount never read). Console: a commission control
  in the salary dialog + a sales card on /me. **Dev caveat:** the header-trust recipe's fixed actor is
  not a UUID, so `SaleWriter` skips the metric (subject_id is a UUID column); commission accrues only
  over OIDC (real logins carry a UUID sub). **F — cleanup:** discovered restaurant-service carried a
  backlog of pre-existing google-java-format violations (committed via worktree builds that skipped
  `spotlessCheck`); isolated the reformat into its own `style` commit so the feature diff stayed clean.
  Shipped as five commits (1 style + E1 delta + E2 producer + E3–E5 backend + console); each build
  green.
- **Employee management + payroll in the console (2026-07-28)** — the org-unit hub gains an
  **Employees** tab (Odoo-style HR records: create employee→contract→assignment chain with role
  presets [free-text `assignment.role`, no new aggregate], assign-to-outlet, end-assignment,
  masked salary packages, terminate) and a real **Payroll** tab (one-click illustrative setup,
  per-unit run scope, run history with KPIs, masked payslips, labor-cost-by-outlet bars, loud
  ILLUSTRATIVE banner whenever provenance ≠ OFFICIAL). employee-service — which already had the
  engine — gained the console-facing APIs: employee LIST (`?orgUnitIds=` — **the BU rollup is
  CLIENT-computed** from the org tree the console already has; `org_unit_projection` deliberately
  gains no parent_id), assignment END (re-emits `AssignmentChanged` with the new `effective_to`;
  consumers upsert by id — **zero event/schema changes in the whole increment**), the org-unit
  legal-employer lookup, compensation CRUD (create validates contract ownership + **overlap→409**
  because the run SUMS covering packages [double-pay guard]; every read masked, the list
  projection never selects `base_pay_enc`), payroll-setup status/seed (delegating to the existing
  idempotent illustrative seeder), run list per period, the **aggregated** allocation summary
  (SUM per outlet+GL — per-employee rows would leak salary; all-zeros sentinel = UNALLOCATED),
  and the payslip index. Gateway routes all three prefixes DASHBOARD_ROLES (HR is never a POS
  surface). Decisions: runs stay COMPANY-scoped (period+run_seq) — a unit tab runs for its
  employees' ids and lists all company runs; re-run = an ADDITIONAL posting (no reversal event
  yet — UI warns, follow-up); salary reads masked-only (authorized-HR read deferred); HR
  employees separate from Keycloak login users (People tab relabeled "App access"). V6 =
  read-path indexes only. Adversarial fresh-context review: **FAIL → fixed same day.** The
  CRITICAL: the console ran payroll per-unit, but finance treats (period, run_seq) as
  **company-wide supersession** — a higher run REVERSES every earlier ACTIVE run's labor postings,
  so a second unit's run would erase the first unit's labor cost off the ledger. Fix: **a console
  payroll run is always COMPANY-WIDE** (every payable employee regardless of which unit tab; the
  gate checks every active BU seal), and the re-run copy now states the supersession truthfully
  (the MAJOR was that it claimed the exact opposite). Also fixed: same-day salary-replace guard
  (was a dead-end 400), FAILED-run rows labeled in history, ILIKE wildcard escaping on the name
  search. Follow-ups noted: a binding test for the frontend ISO-exponent mirror of Money;
  the k=1 single-employee outlet allocation residual stays as documented/signed-off. Separately
  hardened all user input (server whitelists: NIK exactly 16 digits, bank 6–32 digits, PTKP
  TK0–3/K0–3, employment-type enum, role ≤128, name ≤255 — malformed PII is rejected BEFORE
  encryption; console mirrors them inline and every disabled Save now says WHY, incl. the
  unit-not-synced-to-HR state). Dev gotcha: employee-service's `org_unit_projection` only
  hydrates from events — a reset employee DB misses org units whose events left Kafka; dev
  backfill = COPY org_unit rows across (superuser bypasses RLS).
- **Business-unit verticals — restaurant | carwash | barbershop (2026-07-28)** — `org_unit.vertical`
  (org V6: nullable VARCHAR(32); backfill existing BUs → `restaurant`. **The V6 backfill was
  silently swallowed by FORCE RLS** — Flyway runs as the table owner with no tenant GUC, the
  policy filtered the UPDATE to zero rows, and Flyway reported success; caught on the live dev DB,
  invisible to acceptance tests (they only create rows post-migration). V7 redoes it inside the
  `NO FORCE ROW LEVEL SECURITY` escape hatch — restaurant-service V6 is the fleet precedent —
  pinned by `VerticalBackfillMigrationTest`, which migrates→V5, plants a pre-existing BU over the
  BYPASSRLS admin connection, migrates→latest as the owner role, and asserts the backfill landed.
  **Lesson: any migration UPDATE on an RLS-forced table needs the NO FORCE hatch or a
  self-checking follow-up like V2's `SET NOT NULL`.**): REQUIRED on every
  BUSINESS_UNIT creation path (signup, create-company, add-business, org-units), rejected for
  outlet/team, IMMUTABLE after create (like base currency: `updatable = false`, no PATCH path).
  **Casing decision:** stored/emitted/requested as LOWERCASE module-key strings via a JPA
  `AttributeConverter` (never `@Enumerated`, which would silently store the UPPERCASE enum name —
  the exact casing-trap class the hub increment hit with `org_unit.type`), deliberately aligned with
  entitlement-service's `module_catalog.module_key` vocabulary — though entitlements remain a
  SEPARATE company-level concept, untouched this increment. `OrgUnitCreated/Changed` gained an
  optional `vertical` (`["null","string"] default null`, appended LAST — positional-decode safety,
  pinned by an old-reader contract test); consumers stay opaque. The POS learns the vertical via
  `GET /api/v1/outlets` (`{id, name, vertical}` — parent-BU LEFT JOIN; cashiers can't read the
  dashboard-only org-units endpoint). Console: vertical ChoiceCards on signup/onboarding, a
  BU-only select in the org-tree add dialog, tree/hub badges, and a `requiredVertical` gate on
  POS/Kitchen/Menu that renders a branded coming-soon panel (embedded outlet picker — never traps
  a multi-vertical user) for carwash/barbershop outlets. **Client fails OPEN to restaurant on a
  null vertical** (backfill guarantees it server-side; never brick a POS terminal on cache
  staleness — do not "fix" this). No new ADR: the whitelist + ADR-0012-style semantics are
  recorded here; a real carwash/barbershop POS is a later increment. Adversarial fresh-context
  review: **PASS**, no critical/major (RLS self-join verified leak-free — the policy scopes both
  aliases); follow-ups landed: an OrgUnitChanged old-reader pin for contract-test parity and a
  whitelist-copies cross-link on the Vertical enum. Live-proven E2E: V7 backfill on the dev DB,
  carwash signup → coming-soon POS (EN+ID), outlet switch → full POS, and finance's
  pre-increment jar consuming the new-field events.
- **Org-unit hub — Odoo-style record detail (2026-07-28)** — clicking a BUSINESS_UNIT or OUTLET
  in `/org` now opens `/org/:unitId` (the app's first param route): breadcrumb, sheet header
  (type badge + status + rename/(de|re)activate via dialogs lifted into `features/org/parts.tsx`),
  Odoo-style smart buttons (Outlets / People / This-period Net; an outlet shows a parent-unit
  related-record link), and Segmented notebook tabs. **Overview** = per-unit P&L from the new
  finance `unitpnl` feature (`GET /api/v1/pnl/org-units/{id}` — ONE native query rolls up the unit
  + child outlets via `org_unit_ref.parent_id` [captured in V22, queried for the first time] LEFT
  JOINed to `ledger_posting` with signed FILTER sums; V23 adds the two access-path indexes; 204 /
  zeros-with-currency-hint mirror PnlController; the labor UNALLOCATED sentinel is excluded
  structurally). **People** = new org `GET /api/v1/org-units/{id}/users` (assignments under the
  unit's outlet set in-SQL, identity joined client-side from the cached team list; 404
  unknown/foreign anti-enumeration, 400 TEAM). **Outlets** tab manages children inline (Add preset
  to the BU). **Expenses/Payroll** ship as coming-soon panels — no expense producer exists and
  employee-service is not gateway-routed. Zero gateway work (both endpoints under already-routed
  prefixes). Live-E2E-caught fix: `org_unit_ref.type` stores the EVENT value — the enum NAME,
  UPPERCASE (`OrgUnitCreatedSchema` emits `.name()`), while V22 comments + one contract fixture
  misleadingly suggested lowercase; the rollup predicate is now case-insensitive. Also deflaked
  `OrgUnitRefConsumeAcceptanceTest` (its cross-topic drain marker guaranteed no ordering; the
  tests now await the expected state). Review (fresh context) FAILED once on a REAL money bug the
  E2E's untaxed sale could not show: the rollup summed `ledger_posting.amount_minor` — the GRAND
  TOTAL (incl. service charge + tax) — while every other surface reports NET revenue; fixed by
  sourcing the revenue leg from the `outlet_revenue` NET accumulator (the same source
  `/pnl/outlets` serves; reversal netting lives in the accumulator) with expenses still signed-
  summed from the ledger, pinned by a regression test posting a fully-taxed Phase-2 sale
  (106k gross / 90k net). Re-review deltas also added the closed-assignment exclusion test and a
  smart-button error state. Follow-ups: expense-entry slice (producer + UI) and the HR/payroll
  console (needs gateway routing + list endpoints + tax-SME statutory figures) unlock the two
  disabled tabs; W2 (positively re-prove duplicate-delivery consumption in the deflaked consume
  test, e.g. via processed_event) noted as nice-to-have.
- **Org-tree flattening — outlet IS the branch (ADR 0012, 2026-07-28)** — nine atomic commits
  remove the BRANCH level (`business_unit > outlet > team`; `OrgUnitType` is the single encoding),
  seed **one default OUTLET under every new business unit** (company bootstrap AND add-business,
  named after the BU, same tx + own OrgUnitCreated via the outbox), and delete the console's silent
  business-unit fallback: POS/Menu/Kitchen render inside a new **OutletGate** fed by a shared
  `useResolvedOutlets` hook (company outlets ∩ own assignments + outlets[0] self-heal, hoisted out
  of OutletPicker so gate and picker cannot disagree) — `businessId` on a POS surface is now ALWAYS
  a real outlet id. The ignored `firstBusinessType`/`type` came off the signup/create-company wire
  (old bodies still accepted — unknown JSON fields ignored, proven by test); the signup/onboarding
  business-type picker (dead UI) was removed. Wire compat: the events' `type` is a free Avro string
  — doc-string/catalog-only changes; consumers store type opaquely (proven by their contract
  tests). Migration-comment edits changed Flyway checksums → dev stack reset documented in RUNBOOK
  ("2026-07 org-tree flattening — dev data"), which also gives the keep-data per-BU outlet recipe.
  Follow-up (unchanged): restaurant-service still trusts client `businessId` (no org ref table to
  enforce type=OUTLET server-side). Review (fresh context) = PASS; deferred suggestions: treat a
  `/users/me/outlets` load error as a gate error instead of fail-open to the full outlet list
  (reads only — sale writes are backstopped by OutletAccessGuard); memoize `useResolvedOutlets`'
  outlets array; drop the now-unused `idx_org_unit_ref_company_type` in a future migration; add a
  create-company old-body back-compat test mirroring the signup one.
- **Outlet-scoping increment — the org tree means something (phases 1–5, 2026-07-27)** — five
  independently-shipped phases make `business_id` a real, named, enforced outlet dimension.
  **P1** finance `outlet_revenue` read model (keyed company/outlet/period/currency, fed by the same
  SaleRecorded consumer + void/refund reversal path) + `GET /api/v1/pnl/outlets`. **P2** finance
  consumes OrgUnitCreated/Changed into `org_unit_ref` → `/pnl/outlets` gains `outletName`; dashboard
  per-outlet panel shows real named rows. **P3** POS outlet picker (per-tab sessionStorage,
  `CompanySession.activeOutletId`, "ringing for «Outlet»" indicator) + org `GET /api/v1/outlets`.
  **P4** org `user_outlet_assignment` (user_id = KC sub, effective-dated, V5) +
  `GET/PUT /api/v1/users/{id}/outlets` + `/users/me/outlets` + Team outlets editor; picker intersects
  with the caller's assignments. **P5 (NEW EVENT + enforcement)** `UserOutletAssignmentChanged`
  (org outbox, emitted inside the replace-set tx; Avro in libs/contracts; partition key =
  `assignment_id` → per-(user,outlet) ordering, NOT per-user) consumed by restaurant into
  `user_outlet_assignment_ref` (V14, Auditable+FORCE RLS; `processed_event` V15 for event-UUID
  idempotency; DLT fail-closed on missing/non-UUID id header or undecodable payload). Enforcement
  policy (signed off): owner/manager bypass; cashier default-closed with an ACTIVE
  (actor, businessId) row required; grandfather = company with ZERO ref rows (scoping never
  adopted) → allow. The guard (`OutletAccessGuard`, outletref.service) covers EVERY sale-recording
  path — OrderWriter checkout/park/payParked AND BillWriter open/payBill (the bill gap was found
  post-resume and closed: bills record sales via SaleWriter and would otherwise sidestep the
  order-path guard). 403 = RFC-7807 `…/outlet-not-assigned`, mapped to i18n copy (en/id) on all four
  POS surfaces; Team page renders cashier+0-assignments as an amber warning (owner/manager keep
  "All outlets" — they bypass). Proven: full suites green (enforcement 9/9 branches, contract triad,
  consumer idempotency + cross-tenant isolation, producer outbox 4/4, listener fail-closed 4/4) AND
  live E2E on the real stack: org PUT → outbox → Debezium (`org-outbox-connector`, registered
  2026-07-27) → Kafka → restaurant ref row → curl checkout as an unassigned cashier = 403
  problem+json / assigned outlet = sale recorded. Code-review PASS (fix-round applied: partition-key
  contract corrected in catalog+avsc, readiness comment de-overstated, dead code removed).
  **Known limitations (deliberate):** (1) the `kafka` readiness indicator checks broker
  reachability only — on a first deploy with earliest-offset replay, enforcement can briefly run
  against a partially-hydrated ref table (a caught-up/lag gate is a tracked follow-up); (2) the
  guard runs before the idempotency fast path, so a retry of an already-completed order after
  mid-shift revocation returns 403 rather than replaying the original response (security-first
  ordering, accepted); (3) restaurant-service is the only enforcing vertical so far.
- **Outlet-enforcement hardening — close the SaleWriter choke-point bypass (2026-07-27, follow-up
  to phase 5)** — a post-commit adversarial bug hunt (two independent agents) found the phase-5
  guard was on `OrderWriter`/`BillWriter` but NOT on `SaleWriter`, the choke point every
  revenue-recognizing path funnels through. Two real, cashier-reachable bypasses: **(F1, critical)**
  the legacy `POST /api/v1/sales` (`SaleController → SaleWriter.create`, client-supplied
  `businessId`, gateway-routed to `POS_ROLES`) minted `SaleRecorded` at ANY outlet with no check;
  **(F2, high)** `PaymentCaptureWriter.capture → SaleWriter.recordInCurrentTx` recognized digital
  revenue with no outlet re-check. Fix: `OutletAccessGuard.enforce(businessId)` at both `SaleWriter`
  entry points — in `create` placed AFTER the idempotency fast path (an idempotent replay of an
  already-recorded sale still returns 200; only a NEW sale at an unassigned outlet is rejected), in
  `recordInCurrentTx` at the top (the sole guard for capture). The `OrderWriter`/`BillWriter` guards
  stay as fail-fast + coverage for the no-sale paths (park, bill-open). Tests: direct-sale 403 +
  grandfather-allow, capture 403 + assigned-capture success.
  **Documented-not-fixed findings (both hunts, tracked for a later increment):** (a) the
  grandfather clause fails OPEN if the local ref cache diverges from org-service (consumer down /
  DLT / lag → `countAllForCompany()==0` → allow) — adoption is inferred from cache cardinality, not
  an explicit company flag; (b) the **grandfather cliff**: the company's FIRST-ever assignment
  flips every never-assigned cashier to default-closed mid-shift with no "you are enabling
  enforcement for the whole company" signal; (c) roles come from the `X-Roles` header, not the
  validated JWT, so the owner/manager bypass is only as strong as network isolation while tenant
  isolation is token-bound (matters only off-gateway; mTLS deferred); (d) the guard-before-
  idempotency ordering on the order/bill call sites (F2/#4) can 403 a legitimate retry after
  mid-shift revocation; (e) the `/users/me/outlets` gateway route isn't method-constrained (latent).
  Verified CORRECT by the hunts (no change needed): consumer ordering/reopen (stable `assignment_id`
  per tuple), `processOnce`+upsert atomicity, the multi-outlet replace-set diff, epoch-day/sentinel
  range, and RLS fail-closed on an unset GUC.
- **Signup flow hardening — enterprise-gap fixes (2026-07-25)** — closed the register-flow gaps found
  in the Odoo/enterprise gap analysis. Backend (org-service): server-side whitelists for
  currency/language/business-type (`@Pattern` — a direct API call can no longer create an EUR or
  `"xx"`-language tenant); ToS consent required (`termsAccepted` `@AssertTrue`, consent instant
  recorded as the Keycloak `terms_accepted_at` user attribute); email verification support
  (`emailVerified=false` + config-gated `VERIFY_EMAIL` required action via
  `native.keycloak-admin.require-email-verification` — default false because dev has no SMTP,
  **production must enable it**; the signup response carries `emailVerificationRequired` so the UI
  shows the right success state); **signup flow inverted** (Keycloak user FIRST under a
  pre-generated company id, company second) so the failure residual flips from an
  uncompensatable orphaned tenant row (bootstrap events already outboxed) to a single idempotent
  compensating `deleteUser` — only a double failure leaves an orphaned KC user (ERROR-logged);
  Keycloak admin token cache got a lock-free fast path (was: every admin call synchronized).
  Gateway: the public `/api/v1/signup` route is now throttled by a dedicated
  `AnonymousRateLimitFilter` — per-client-IP Redis token bucket (`rate-limit.signup.*`, default
  10/hour burst 10), spoof-safe by default (X-Forwarded-For honored ONLY when
  `trust-forwarded-for` is explicitly enabled, and then only its last entry). Console: signup
  rework — 4 consolidated steps (was 5), real `<form>` per step (Enter advances), confirm-password +
  dependency-free strength meter, ToS checkbox, review rows link back to their step, all API errors
  mapped to i18n keys (raw English RFC-7807 details no longer shown to id-locale users), and
  post-signup sign-in passes `login_hint` so Keycloak pre-fills the just-registered email.
  Proven: SignupAcceptanceTest (8, real KC26+PG), GatewaySignupRateLimitTest (2, real Redis),
  AnonymousRateLimitFilterTest (4 — incl. the multi-line XFF flattening regression from the
  security review's LOW finding); both service suites + console build green; security-engineer
  review PASS. Remaining
  follow-ups (deliberate): CAPTCHA/Turnstile on top of the IP throttle, realm SMTP config +
  enabling verification in prod, idempotency-key on the signup POST, funnel analytics.
- **Fix: `management.tracing.export.enabled=false` broke W3C propagation (ADR 0010 #13, 2026-06-22)** —
  Root-cause found and fixed. `TraceContinuityConsumeAcceptanceTest` was consistently failing: every
  Kafka listener started a new root span instead of continuing the producer trace. Investigation via
  Spring Boot 4.1 source: `@ConditionalOnEnabledTracingExport` gates the W3C `TextMapPropagator` bean
  in `OpenTelemetryPropagationConfigurations`; when `management.tracing.export.enabled=false`, that
  condition evaluates false, so only `TextMapPropagator.noop()` is registered via `NoPropagation`,
  making `OtelPropagator.extract()` always return an invalid span context (→ new root span). The
  property was set in `ObservabilityEnvironmentPostProcessor` to suppress OTLP connection-failure
  noise, but was unnecessary: the OTLP span exporter is never created without an endpoint, because
  `OtlpTracingConfigurations.Exporters` requires `@ConditionalOnBean(OtlpTracingConnectionDetails.class)`
  and that bean only materialises when `management.opentelemetry.tracing.export.otlp.endpoint` is
  set. Fix: removed `management.tracing.export.enabled=false` from the post-processor defaults.
  Only `management.otlp.metrics.export.enabled=false` remains (disabling the OTLP metrics push
  registry). `TraceContinuityConsumeAcceptanceTest` now passes; ADR 0010 and `build.gradle.kts`
  comments updated to explain the constraint.
- **Trace continuity through the CDC pipeline + outbox-lag metric (ADR 0010 #13, 2026-06-22)** —
  Closed the outbox→Debezium→Kafka trace gap: producer services now stamp the W3C `traceparent`
  (current Micrometer span) into a new nullable `traceparent VARCHAR(64)` column on every `outbox`
  table (one Flyway `ALTER TABLE` migration per the 7 producer services). `libs/events` gained a
  `TraceparentSupplier` functional interface, a `MicrometerTraceparentSupplier` implementation
  (reads from `io.micrometer.tracing.Tracer`; both are `compileOnly` so DB-only modules are
  unaffected), and an updated `OutboxWriter` that accepts the supplier. `OutboxRecord` was extended
  with a nullable `traceparent` field (not in `requireNonNull`). All 7 `EventsConfig` classes were
  wired with `ObjectProvider<Tracer>` (degrades to NOOP if no Tracer present). The Debezium connector
  template (`docker/debezium/outbox-connector.json`) appends `traceparent:header:traceparent` to
  `table.fields.additional.placement` so Debezium maps the column to a Kafka header. All 5 consumer
  `KafkaConfig` classes set `factory.getContainerProperties().setObservationEnabled(true)` on the
  custom container factory, and `spring.kafka.listener.observation-enabled: true` was added to each
  consumer's `application.yml` — so Spring Kafka extracts the header and makes the listener span a
  child of the producer span via the Micrometer OTel bridge. The outbox-lag gauge
  `native.outbox.unpublished` (tag: `service`, COUNT WHERE published_at IS NULL via the existing
  partial index) was added as `OutboxLagMetrics` in `libs/events` and registered in every producer's
  `EventsConfig`. **Dependency decision:** `micrometer-tracing` and `micrometer-core` added as
  `compileOnly` in `libs/events` — the gateway depends on `libs/observability` (not `libs/events`),
  so the DB-free gateway stayed clean; services already have both at runtime via
  `libs/observability`'s `api("spring-boot-starter-opentelemetry")`. **Tests:** (a)
  `OutboxWriterTraceparentTest` — four unit tests on H2 proving supplier-present → stored, supplier-null
  → NULL, NOOP constructor → NULL, direct-record write → round-trip; (b)
  `TraceContinuityConsumeAcceptanceTest` — consumer-side Testcontainers Kafka + PostgreSQL 16 test
  that publishes a record with a known `traceparent` header and asserts the listener span's traceId
  equals the header's traceId (proves framework propagation end-to-end). `docs/EVENT-CATALOG.md`
  updated with the `traceparent` header row. **Remaining of #13:** OTLP collector + Grafana (handled
  by the parallel observability backend work stream).
- **Observability backend + dashboards — Prometheus / Grafana / Tempo overlay (scorecard #13, 2026-06-22)** —
  Added the metrics/trace BACKEND infra as a composable Docker Compose overlay (`docker/compose.observability.yml`)
  that sits alongside the dev stack but does not bloat `compose.dev.yml`. Three containers: **Prometheus**
  (`prom/prometheus:v2.53.3`) scraping `/actuator/prometheus` on all 8 services at 15 s intervals (one job
  per service, `service` label), **Grafana Tempo** (`grafana/tempo:2.6.1`) receiving OTLP spans over HTTP
  port 4318 / gRPC port 4317, and **Grafana** (`grafana/grafana:11.3.2`) with fully provisioned datasources
  (Prometheus + Tempo, trace-to-metrics correlation wired) and three dashboards: `native-red.json` (RED
  method per service from `http_server_requests_seconds_*`, templated `$service` variable), `native-events.json`
  (Kafka consumer lag via `kafka_consumer_fetch_manager_records_lag`, listener throughput/latency via
  `spring_kafka_listener_seconds_*`, outbox lag via `native_outbox_unpublished` gauge — the gauge itself is
  authored by a parallel work stream), and `native-jvm.json` (heap/non-heap, GC pause, CPU, threads, buffer
  pools from standard Micrometer JVM metrics). OTLP export remains **OFF by default** (ADR 0010 decision
  preserved — the SDK is real and trace IDs populate MDC, nothing is shipped until an operator sets
  `MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=true` + `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces`).
  All YAML validated with `python3 -m yaml.safe_load`; all dashboard JSON validated with `python3 -m json.tool`.
  **Config-only / author-only** — same status as the rest of `docker/` (not exercised against a live
  multi-service run; a real cluster would need port assignments confirmed and Tempo storage adjusted).
  Closes scorecard **#13** for the local dev observability backend half (Vault/Linkerd at the service-split
  point remain deferred per CLAUDE.md). See `docker/README.md §Observability stack` for bring-up instructions.
- **Distributed tracing — Micrometer Tracing + OpenTelemetry fleet-wide (ADR 0010, scorecard #10)** —
  §5 called for OTel trace context across every hop + `traceId`/`spanId` in the JSON logs, but **no
  tracing was wired**: every log line carried an empty `[,]`, and the RFC-7807 advice + error-inbox read
  a `trace_id` MDC key nothing ever populated. Wired **Micrometer Tracing + the OpenTelemetry bridge**
  from `libs/observability` (the one dependency every service + the gateway already has) via
  `spring-boot-starter-opentelemetry` — Spring Boot 4.0 split tracing into per-concern modules, and the
  bare bridge alone falls back to a **no-op tracer**. Aligned the shared logback MDC keys to Micrometer's
  `traceId`/`spanId`, and every `MDC.get("trace_id")` reader (the advice in each service, libs/security's
  `ApiExceptionHandler`, the error-inbox `ConsumeErrorRecorder`) to `traceId` — so the real trace id now
  flows into error responses' `traceId` and `error_log.trace_id` (the DB column keeps its name). Full
  sampling (1.0) via a lowest-precedence `ObservabilityEnvironmentPostProcessor` default; OTLP span +
  metric export **disabled by default** (no collector yet → nothing shipped, no connection-failure
  noise). W3C `traceparent` propagation across the gateway→service sync edge is Spring Boot's
  auto-instrumentation. **Deferred (infra-gated):** the outbox→Debezium→Kafka trace continuity (producer
  stamps `traceparent` into the outbox; connector maps it; consumer extracts it — needs a schema
  migration on every producer + connector changes + the live CDC loop) and a real OTLP collector (#13).
  Closes scorecard **#10** for the OTel + MDC + HTTP-propagation half (ahead of blackheart's custom IDs).
  Verified: a `TracingWiringTest` (real Tracer + 1.0 sampling + a valid 32-hex W3C trace id) + the WHOLE
  fleet's Testcontainers suites green across all 8 services + the libs — the fleet-wide classpath +
  MDC-key change re-verified end to end (the employee PII-log drift guard updated to the new allow-list);
  checkstyle + spotless green. With #10 done, the four "then code" scorecard gaps — **9, 12, 11, 10** —
  are all closed; their infra-gated remainders fold into #13.
- **Error-inbox fleet rollout — `libs/error-inbox` (ADR 0009, scorecard #11)** — the finance error-inbox
  pilot (ADR 0005) became a shared library and was rolled out to **every** event-consuming service. The
  five service-agnostic pieces (`ErrorMessageRedactor`, `ErrorInboxWriter`, `AlertPayload`,
  `AlertWebhookClient`, `ConsumeErrorRecorder`) now live once in **`libs/error-inbox`** with an
  `ErrorInboxAutoConfiguration` that registers them (REQUIRES_NEW tx template + a
  `@ConditionalOnMissingBean` Clock; the alert's `service` label comes from `spring.application.name`,
  not a hardcoded constant). A **dedicated lib, not `libs/observability`** — the stateless gateway
  depends on observability and must stay DB-free; error-inbox carries JDBC/Kafka/RestClient, so it is
  consumed only by the event-consuming services. **finance** was migrated onto the lib (its in-service
  copies + `ObservabilityConfig` deleted), and **carwash, employee, entitlement, notification** each
  gained it: a `libs:error-inbox` dependency, an `error_log` Flyway migration (per-service DB — NOT
  Auditable, NOT RLS; `company_id` nullable diagnostic context; PII redacted at write time as the
  RLS-substitute mitigation, HR-6 — the ADR 0005 deviations carried forward verbatim), and a one-line
  wrap of the existing DLT `DeadLetterPublishingRecoverer` in a `ConsumerRecordRecoverer` that records
  the failure before publishing. **Deliberate exclusions:** org + restaurant (pure producers, no DLT to
  guard) and the gateway (no consumers, no DB). So a poison money/business event is now recorded
  (fingerprint-deduped) + milestone-alerted (PII-redacted egress) on the WHOLE fleet, from one
  definition. Closes scorecard **#11** for the DB-inbox+alerting half (the RED-metrics/outbox-lag/Grafana
  half stays a follow-up tied to #13). Verified: the lib's pure-unit tests (redaction, milestone
  predicate, fail-safe swallow + PII-safe egress) + finance's `ErrorInboxWriterTest` (the lib bean vs
  finance's real `error_log`) + the full Testcontainers suites of carwash/employee/entitlement/
  notification (each boots with its new migration + the wired recorder) all green; ArchUnit + spotless
  + checkstyle green.
- **Client resilience — explicit outbound timeouts + startup self-check (scorecard #12)** — closed the
  "every outbound client sets explicit connect/read timeouts" gap (ENGINEERING-STANDARDS §4). Business
  services talk only via events (HR-2), so the outbound HTTP surface is just the **Keycloak JWKS fetch**
  and the **finance alert webhook**. *(Security review caught a wrong premise mid-implementation and it
  was corrected before commit: on the pinned Spring Security 7.1.0, `NimbusJwtDecoder.withJwkSetUri(...)`
  is NOT infinite — its default `RestTemplateWithNimbusDefaultTimeouts` already bounds the fetch to
  500ms/500ms via Nimbus' `RemoteJWKSet.DEFAULT_HTTP_*`. So this is not an infinite-hang fix; an earlier
  draft defaulting to 2s/3s would have **loosened** the framework's 500ms — a regression — and was
  reverted.)* What the change delivers: the shared `libs/security JwtSecurityConfig` (every business
  service) and the gateway's own `JwtDecoderConfig` feed EXPLICIT timeouts into the decoder's
  `restOperations`, sourced from `@Validated @ConfigurationProperties` (`native.security.jwks.*`,
  `@NotNull`, defaults **500ms/500ms** to MATCH the framework — never a back-door loosening). The value
  is now owned config: externalized (a slow-Keycloak env can widen it with no code change, §7), asserted
  positive at boot, and immune to a silent shift if a future library bump changes the framework default.
  A new `OutboundClientTimeoutCheck` (registered in `NativeSecurityAutoConfiguration`, runs in every
  profile) **fails fast at boot** on a null/zero/negative timeout (`0s` = infinite in
  `SimpleClientHttpRequestFactory`); the gateway carve-out (no libs/security dep) makes the same
  assertion in its decoder constructor. The finance `AlertWebhookClient` already carried explicit
  timeouts (ADR 0005). Verified: fail-fast unit tests (libs/security + gateway) + the existing
  real-Keycloak JWKS proofs (libs/security defense-in-depth + gateway JWT routing + org secured
  bootstrap) all green — the `restOperations`-wired decoder still validates tokens end-to-end. Scorecard
  **#12 → ✅**.
- **OpenAPI docs — springdoc fleet rollout + `@Operation` ArchUnit enforcer (ADR 0008)** — the finance
  springdoc pilot (ADR 0004) became the fleet standard. Every service exposing a business REST API —
  **org, restaurant, carwash, employee, entitlement** + the **finance** pilot (6 services) — now serves
  `/v3/api-docs` (OpenAPI 3.1) + `/swagger-ui`, with an `@Operation` on **every** handler (**56 handlers
  across 20 controllers**) and a class-level `@Tag` on each `@RestController`. A new
  `apiHandlersAreDocumentedWithAnOperation` ArchUnit rule in each `LayeredArchitectureTest` — and in
  `service-template` (with `allowEmptyShould` so a fresh clone inherits it) — fails the build on any
  `@RequestMapping`/`@GetMapping`/… handler missing an `@Operation` (the `config`
  `HealthController`/`/healthz` is exempt via `resideOutsideOfPackage("..config..")`). A per-service
  `OpenApiDocsSmokeTest` boots the real service and asserts `/v3/api-docs` is genuine OpenAPI JSON (not the
  Base64 blob the Boot-3 springdoc 2.8.x line returns on Framework 7) documenting the live endpoints — six
  smoke tests now guard the shared catalog-pinned springdoc version across the fleet. **Deliberate
  exclusions:** notification-service (no business REST API — a pure event consumer) and the reactive
  gateway (would need the webflux starter; routes no endpoints of its own). OpenAPI annotations are
  **developer-facing docs, so HR-9 i18n does not apply**. Closes competitive-scorecard **#9** (Native ≥
  blackheart: springdoc + `@Operation`/`@Tag` everywhere, PLUS an ArchUnit enforcer + smoke tests, where
  blackheart relies on discipline). Verified green across the 7 touched modules: compile + every
  `LayeredArchitectureTest` + every `OpenApiDocsSmokeTest` (Testcontainers) + checkstyle + spotless.
  Follow-up (ENGINEERING-STANDARDS §1.3): `@ApiResponse`/`ProblemDetail` error-response modelling + a
  "generated spec ⊇ published spec" contract test.
- **Robust restaurant POS — 4 phases (ADR 0006/0007)** — the validation-slice POS (menu → atomic
  order → `SaleRecorded`) became a real point-of-sale, built + adversarially-reviewed phase by phase
  (every phase's mandatory money/tenancy review FAILED first and caught a real bug; all fixed +
  tested). **P1 Payments:** a provider-agnostic tender port — **cash live** (tendered + change), **QRIS/
  card flagged-pending** (a `DigitalProvider` that never moves money; real adapter deferred to ADR
  0007), the load-bearing **revenue-recognised-at-capture** invariant (a digital tender is PENDING
  with no sale until capture), and **void/refund** driving a balanced finance reversal. *(Review caught:
  the void/refund events had no finance listener — reversals were dead in prod.)* **P2 Pricing:** an
  order price breakdown (PB1 restaurant tax + service charge + order discount), round-once `Money`
  math, posted to the GL as a balanced 5-leg entry (tax→liability, discount→contra-revenue), with a
  read-only `/orders/quote` for the live cart total. *(Review caught: a void under-stated revenue
  (unwound by gross not net) and a refund left tax over-collected.)* **P3 Catalog:** categories,
  per-item modifiers/variants with price deltas, 86'ing/availability. *(Review caught: a quote↔checkout
  price drift; a back-fill migration dead under FORCE-RLS.)* **P4 Order ops:** dine-in/takeaway/delivery,
  tables + occupancy, **hold/park → resume → pay-parked** (no revenue until pay), printable receipt.
  *(Review caught: the gateway lacked a `/tables/**` route; pay-parked dropped the tax split.)* All
  indirect-tax law is **flagged-illustrative** (PB1≈10% vs PPN 11%, service-charge-as-revenue-vs-tip,
  COA mappings — `ILLUSTRATIVE_PLACEHOLDER` + `uses_illustrative_rules` propagated to the books and
  badged "Estimated" in the UI; an SME must confirm). Verified live end-to-end (Keycloak → gateway →
  restaurant → finance): a cash sale with a Size modifier on a dine-in table, tax breakdown, receipt.
  restaurant 155 · finance 323 · gateway 22 tests green.
- **Error-inbox + webhook alerting (finance pilot, ADR 0005)** — a DLT'd money event is no longer a
  silent failure. The Kafka DLT recoverer records each consume failure into a per-service
  `error_log` ops table (V14) via a fingerprint-deduped `INSERT … ON CONFLICT` upsert
  (`ErrorInboxWriter`, plain JdbcTemplate in a REQUIRES_NEW tx so it survives the rolled-back
  business tx), then fires an async webhook alert on occurrence-count milestones (1/10/100/every-1000;
  no-op when the URL is unset, so dev/CI never call out). Deliberate, ADR-recorded deviations:
  `error_log` is NOT Auditable and NOT RLS-scoped — it is cross-tenant **operator** data, `company_id`
  nullable context never an access key; the HR-6 mitigation in place of RLS is **PII redaction at
  write time** (email + ≥10-digit runs incl. space/hyphen-separated). Code-reviewed (money/tenancy
  gate) → fixed a **blocker**: the alert webhook had shipped the RAW exception message off-box; it now
  carries only the redacted message + the real dedup fingerprint, the upsert runs under a bounded tx
  timeout (no partition stall), and a payload test guards the egress. Closes scorecard **gap #11** for
  finance; fleet rollout + Grafana dashboards (#13) deferred to a follow-up ADR. 279 finance tests
  green. (commits `cd4d744` + `e51325c`)
- **OpenAPI docs — springdoc pilot (finance-service)** — finance now serves `/v3/api-docs`
  (OpenAPI 3.1) + `/swagger-ui`, generated from the live controllers. Pinned **springdoc-openapi
  3.0.x** — the Boot 4 / Framework 7 line; an earlier probe's 2.8.x (Boot 3) returns a Base64-mangled
  `/v3/api-docs` on Framework 7. An `OpenApiDocsSmokeTest` boots the service and asserts the endpoint
  is real OpenAPI JSON (not Base64) documenting the statements paths, so a Boot/springdoc bump that
  breaks doc generation fails the build. Docs sit behind the JWT chain and are not gateway-routed
  (dev/in-cluster only). Decision recorded in **ADR 0004**; fleet-wide rollout is a later ADR.
- **Financial Statements (Income Statement + Balance Sheet)** — finance gains a read-only statements
  API derived ENTIRELY from the double-entry GL (no new tables, no migrations). `GET
  /api/v1/statements/income?period=YYYY-MM` is period-scoped (REVENUE−EXPENSE=net, via
  `GlTrialBalanceReader`); `GET /api/v1/statements/balance-sheet?asOf=YYYY-MM` is CUMULATIVE — a new
  `JournalEntryRepository#glTrialBalanceAsOf` native query + `GlCumulativeTrialBalanceLineView`
  projection sums all journal activity where `period <= :asOf`. **Retained earnings is computed on
  read** (Σrevenue − Σexpense cumulative; never posted to the GL) so the sheet balances, with a
  defence-in-depth `assets == liabilities + equity` gate that throws on imbalance (an internal
  posting bug → 500). Sign conventions per `AccountType` (ASSET/EXPENSE debit-normal,
  LIABILITY/EQUITY/REVENUE credit-normal); all amounts `long` minor units + `Math.*Exact` (HR-8).
  Tenant-scoped via RLS only — readers are `@Transactional`, no manual `WHERE company_id`; a tenancy
  isolation test proves A's books are invisible to B. Code-reviewed → fixed: a multi-currency trial
  balance is now a TYPED `GlMultiCurrencyException` → **422** (was a bare `IllegalStateException` →
  opaque 500; mirrors the within-close `MultiCurrencyTrialBalanceException`), and an unmapped account
  a typed `GlUnmappedAccountException` (→ non-leaking internal 500, like `UnmappedLedgerAccountException`);
  added the missing `StatementsControllerTest` web-slice (200/204/400/422 + RFC-7807 shape) for parity
  with the sibling controllers. 248 finance tests green. The gateway routes + **owner/manager
  role-gates** `/api/v1/statements/**` → finance-service (a new `statementsRoute`, mirroring the
  pnl/revenue dashboard routes; a cashier is denied 403 at the edge), with a role-routing test. The
  **console** adds two owner/manager pages — Income statement (`/statements/income`) and Balance
  sheet (`/statements/balance-sheet`) — dashboard-style (KPI tiles + a bar chart + an expandable
  per-account breakdown), en/id, Intl money via the shared `money.ts`, reusing the illustrative
  badge; `tsc` + `vite build` green (also fixed a latent type error in the console's dev-proxy
  config). SME-deferred: SAK-EMKM presentation grouping/labels, comparative columns, Cash Flow
  statement.
- **Double-entry General Ledger** — finance gains a real double-entry GL (`journal_entry` + balanced
  `journal_line`, the invariant enforced in the aggregate so an unbalanced entry can't exist)
  ALONGSIDE the existing dimensional ledger (untouched). Every money event auto-posts a balanced
  journal in the SAME consume transaction via a data-driven posting-template framework — SME-gated:
  the Indonesian COA / account mappings / tax are higher-version effective-dated rows, with a loud
  flagged-illustrative seed today; the GL trial-balance read proves Σdebit==Σcredit. Code-reviewed →
  fixed (authoritative-period vs `periodOf(occurredAt)`, fail-loud on an unmapped account,
  illustrative-flag OR). V13. (commits `04fb971` + `ee3f400`)
- **Console production auth + POS** — real Keycloak OIDC (authorization-code + PKCE) login in the
  console; the SPA sends a bearer to the gateway, which now **role-gates** routes (cashier → the
  restaurant POS routes; owner/manager → the finance/org dashboard routes via a `RoleAuthorizationFilter`)
  and the SPA gates its surfaces by role (cashier → POS only). New restaurant **POS** (menu + atomic
  order checkout reusing `SaleRecorded`). Fixed a 100× IDR bug (`money.ts` fraction digits ≠ libs/money).
  Added org `GET /api/v1/companies/current`, a `native-console` PKCE realm client, a console Docker
  image + Kustomize overlay + CI job, and the **≥-blackheart standards scorecard** (a maintained
  competitive bar) to ENGINEERING-STANDARDS. Verified live end-to-end (owner sees dashboard+POS;
  cashier POS-only with the dashboard 403'd at the gateway; forged tenant headers stripped).
- **Org-tree move/deactivate semantics (#25)** — the undecided lifecycle semantics, resolved by user
  decision: deactivation **cascades** to the active subtree (one `DEACTIVATED` event per node), and a
  node can be **reactivated** (`REACTIVATED`, requires an active parent, no cascade down). Enforces a
  single invariant — *no active node may have an inactive ancestor* — across all four structural ops
  (cascade-deactivate; reactivate-needs-active-parent; can't move an active node under an inactive
  parent; can't create under an inactive parent). New `REACTIVATED` `change_kind` is a backward-compat
  Avro `string`; employee-service consumes via the `active` flag (no consumer change). Producer-only
  change, no migration. Adversarially reviewed (PASS; closed the create-guard gap it found); org +
  employee builds green.
- **Finance-expansion robustness — posting-currency guard (#26)** — a `SaleRecorded`/`ExpenseRecorded`
  in a currency other than the company's immutable base currency used to silently create a SECOND
  `consolidated_pnl`/`consolidated_revenue` row (keyed on `(company, period, currency)`), detonating
  later as a raw read-time `500` in `PnlReader`. Added a write-time guard (`PnlReadModelWriter
  .requireConsistentCurrency`, RLS-scoped) on the revenue+expense writers: a divergent posting throws
  a typed, non-retryable `MismatchedPostingCurrencyException` → the consume rolls back (no divergent
  row) and the record is DLT'd (fail-closed, money held, read stays clean). Mirrors the labor path's
  `CURRENCY_MISMATCH` guard + the within-close `MultiCurrencyTrialBalanceException`; the `PnlReader`
  multi-currency branch stays as a defense-in-depth backstop. Adversarially reviewed (PASS); full
  finance build green.
- **Console read-endpoints: org tree, group consolidation, period close (2026-06-22)** — added three
  GET endpoints required by the console dashboard pages: `GET /api/v1/org-units` (flat org-unit list,
  RLS-scoped, native+projection), `GET /api/v1/consolidation-groups` (groups the current company leads)
  and `GET /api/v1/consolidation-groups/{groupId}/members` (group members, 404 if not led by caller)
  in org-service; `GET /api/v1/closes` (close history, most-recent first) in finance-service. Gateway
  routes added for all four with `DASHBOARD_ROLES` (`owner`, `manager`). Projection-to-DTO mapping
  kept strictly in the service layer (`OrgUnitReader`, `GroupReader`, `CloseHistoryReader`) per
  CODE-STRUCTURE §3.3 (ArchUnit `featureLayersRespectTheLayeredArchitecture` enforces no Dto→Projection
  access). `WithinCompanyCloseControllerTest` updated to declare `@MockitoBean CloseHistoryReader` for
  the new controller constructor arg. org-service 93/93 tests green; finance-service 298/299 green
  (1 pre-existing `TraceContinuityConsumeAcceptanceTest` flakiness from ADR 0010 tracing, unrelated).
- **P3d deferred operational items (#46)** — (a) V12 backfills the non-RLS `member_group_index` from
  the FORCE-RLS `group_member` for pre-V10 memberships (whose `GroupMembershipChanged` was already
  deduped and will never re-fire, so a within-company close would silently emit no
  `TrialBalancePublished` for them); the migration uses a `NO FORCE`/`FORCE` bracket so the
  table-owning role can read across tenants at migrate time (a plain FORCE-RLS read sees zero rows
  with no GUC bound). (b) An advisory lock (`pg_advisory_xact_lock`, mirroring the labor primitive) on
  the within-company close serializes concurrent closes so the loser returns the idempotent no-op,
  not a UNIQUE-violation 500. (c) Web-slice MVC tests for `WithinCompanyCloseController` (200/422/400)
  + a concurrency regression test + a backfill projection test. Adversarially reviewed (PASS); full
  finance build green.
- **Live end-to-end validation + 3 CDC fixes** — ran the real outbox→Debezium→Kafka→finance loop;
  found+fixed the publication-mode, occurred_at-timestamp, and bytea/ByteBuffer (base64) bugs the
  stubbed-relay tests couldn't catch. Proven: `GET /api/v1/revenue` = the recorded sales.
- **Follow-up hardening sweeps** — consolidation money-math regression-locks; FX/mapping resolution
  determinism + a group-RLS migration-lint guard; P3d tenancy/predicate/typed-fault hardening; platform
  defense (show-details pinned, encoded-JSON PII guard, readiness composition, RLS-bean presence);
  payroll/labor guards (mixed-grain, top-bracket-cap, control-total currency, looped race).
- **CI + deploy (#24)** — GitHub Actions (build+test+image matrix) + Kustomize base+overlays + fixed the
  broken service Dockerfiles + the missing entitlement DB. Author-only / unverified vs a real cluster.
- **Layer-subpackage refactor** — every feature split into controller/service/repository/domain/dto/
  messaging; ArchUnit retargeted; service-template + docs updated. Pure move, 624 tests green.
- **Phase 3 (P3a–P3d)** — payroll engine (flagged statutory) · finance consumes labor cost (supersession,
  concurrency-safe) · FX/multi-currency (non-float FxRate, flagged stub) · group consolidation
  (two-GUC RLS, intercompany elimination, FX translation, the ConsolidationClosed producer — closing the
  loop notification consumes). 5 seams, each gated + fixed + committed.
- **#14 cross-cutting hardening** — JSON structured logs, readiness probes, RLS-ordering + anti-redeclare
  + float-ban ArchUnit guards.
- **Phase 2** — entitlement-service + the gate lib · full org tree + legal_employer · employee records
  (PII field-encryption) · carwash (2nd vertical) · finance expansion (mapping rules + dimensional
  ledger + expenses) · notification-service.
- **Phase 1 (validation slice)** — gateway + Keycloak (M1.1) · org-service create-company (M1.2) ·
  restaurant record-sale (M1.4) · finance consume → consolidated revenue (M1.5) · event transport (M0.4).
- **Phase 0** — Gradle monorepo + Java 25 toolchain · shared libs (money/tenant/events) · service-template
  · the quality gates · the engineering-standards + code-structure docs.
