# Architecture Decision Records (ADRs)

This directory is the **append-only log of significant architecture decisions** for Native. The stack
in `CLAUDE.md` is pinned "no change without an ADR" and the tech-lead agent "owns ADRs" — this is
where those records live, so a decision and its *why* are discoverable instead of buried in prose.

## When to write one
Write an ADR when a choice is **cross-cutting, hard to reverse, or sets a convention** other code must
follow: a stack/library pin, a persistence or tenancy rule, an event-contract pattern, a service
boundary, a security posture. Routine, local choices do not need one — use a code comment or DEVLOG.

## How
1. Copy `0000-template.md` to `NNNN-short-title.md` (next zero-padded number).
2. Fill in Context / Decision / Consequences. Keep it short — one screen is ideal.
3. Status starts `Proposed`; set to `Accepted` when adopted. Never edit an Accepted ADR's decision —
   supersede it with a new ADR and set the old one's status to `Superseded by NNNN`.
4. Link it from the relevant doc (CLAUDE.md, CODE-STRUCTURE.md, EVENT-CATALOG.md) if it changes a rule.

## Index
| # | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions in ADRs | Accepted |
| [0002](0002-native-query-and-projection-reads.md) | Repository reads are native queries + projections | Accepted |
| [0003](0003-single-source-event-schemas-libs-contracts.md) | Event Avro schemas have a single source: `libs/contracts` | Accepted |
| [0004](0004-openapi-docs-springdoc.md) | Use springdoc-openapi (3.0.x) for OpenAPI docs, piloted in finance-service | Accepted (pilot; extended fleet-wide by [0008](0008-openapi-docs-fleet-rollout.md)) |
| [0005](0005-error-inbox-and-alerting.md) | Error-inbox + webhook alerting for production-error visibility, piloted in finance-service | Accepted (pilot; extended fleet-wide by [0009](0009-error-inbox-fleet-rollout.md)) |
| [0006](0006-pos-payment-tenders.md) | POS payment tenders — provider-agnostic port, cash live, digital flagged-pending, revenue-at-capture | Accepted |
| [0007](0007-real-psp-adapter-webhook.md) | Real payment-service-provider adapter + settlement webhook (the digital-tender switch) | Superseded by [0045](0045-qris-modes-and-payment-service.md) |
| [0008](0008-openapi-docs-fleet-rollout.md) | Roll springdoc-openapi out fleet-wide with an `@Operation` ArchUnit enforcer | Accepted |
| [0009](0009-error-inbox-fleet-rollout.md) | Extract the error-inbox into `libs/error-inbox` and roll it out fleet-wide | Accepted |
| [0010](0010-distributed-tracing-otel.md) | Wire distributed tracing (Micrometer Tracing + OpenTelemetry) fleet-wide | Accepted |
| [0011](0011-keycloak-admin-via-spring-restclient.md) | Use Spring RestClient for Keycloak Admin API calls (no keycloak-admin-client library) | Accepted |
| [0012](0012-flatten-org-tree-remove-branch.md) | Flatten the org tree: remove BRANCH and seed a default outlet per business unit | Accepted |
| [0013](0013-per-login-page-grants-subtractive-ui.md) | Per-login page grants are subtractive UI gating; roles remain the API authz boundary | Accepted |
| [0014](0014-accounts-receivable-subledger.md) | Accounts Receivable sub-ledger + the customer/party dimension in finance-service | Accepted |
| [0015](0015-accounts-payable-subledger.md) | Accounts Payable sub-ledger — the vendor-facing mirror of AR | Accepted |
| [0016](0016-bank-reconciliation.md) | Bank accounts & reconciliation — settling the clearing account | Accepted |
| [0017](0017-tax-ppn-vat-return.md) | Tax / PPN — the VAT return, filing & settlement | Accepted |
| [0018](0018-org-unit-hard-delete-empty-only.md) | Hard-delete an empty org unit; accept the owner-rung-sales orphan risk | Accepted |
| [0019](0019-cash-flow-statement-and-budgets.md) | Cash Flow Statement (indirect, GL-derived) + per-month Budgets | Accepted |
| [0020](0020-fixed-assets-and-deferrals.md) | Fixed assets & deferrals — the monthly amortization run | Accepted |
| [0021](0021-multi-company-ownership.md) | Multi-company ownership — one login, 1..N businesses (multivalued claim + validated selection) | Accepted |
| [0022](0022-fixed-asset-disposal.md) | Fixed-asset disposal — gain/loss on disposal as other income | Accepted |
| [0023](0023-carwash-vertical-pos.md) | Carwash vertical POS — the ticket flow, vertical path prefixing, PII-free staff attribution | Accepted |
| [0024](0024-barbershop-vertical-and-module-rollout.md) | Barbershop vertical — cloning the carwash POS shape; how a new module rolls out | Accepted |
| [0025](0025-country-driven-company-defaults.md) | Country-driven company defaults — Odoo-style signup, derived base currency, funnel fields | Accepted |
| [0026](0026-promotions-single-discount-collapse.md) | Promotions — per-vertical rules and coupons that collapse into one discount | Accepted |
| [0027](0027-loyalty-service-and-eventual-consistency-redemption.md) | Loyalty and gift cards — a new bounded context; redemption under eventual consistency | Accepted |
| [0028](0028-offline-mode-cash-only-queue.md) | Offline mode — cash-only client queue replayed through the online checkout | Accepted |
| [0029](0029-self-order-qr-and-customer-display.md) | Self-order QR — an anonymous surface whose blast radius is a parked row | Accepted |
| [0030](0030-employee-expense-claims.md) | Employee expense claims — recognition at approval, a new event family, settle-once payable | Accepted |
| [0031](0031-indonesian-statutory-payroll-official-datasets.md) | Indonesian statutory payroll — OFFICIAL canned datasets, the TER-transcription activation checklist, PATCH-creates-new-row overrides | Accepted |
| [0032](0032-payroll-liability-recognition.md) | Payroll liability recognition — a third event, a clearing re-class, and run-type-aware supersession | Accepted |
| [0033](0033-attendance-and-timeoff-foundation.md) | Attendance & time-off foundation — leave requests, overtime entries, and a derived balance | Accepted |
| [0034](0034-pos-shell-verticals-are-adapters.md) | POS shell + shared payment surface — verticals are adapters, not clones | Accepted |
| [0035](0035-thr-run-type.md) | THR run type — the off-cycle Tunjangan Hari Raya allowance | Accepted |
| [0036](0036-register-sessions-and-platform-channel-settlements.md) | Register sessions (closing kasir) + online-channel platform settlements | Accepted |
| [0037](0037-opening-balances-and-business-migration.md) | Opening balances & business migration — the opening balance sheet + brought-forward assets | Accepted |
| [0038](0038-daily-close-all-tender-and-inventory.md) | Daily close v2 — once-per-day, all-tender reconciliation, inventory stocktake | Accepted |
| [0039](0039-direct-thermal-printing-escpos.md) | Direct thermal receipt printing (ESC/POS over WebUSB / Web Bluetooth / WebSerial) | Accepted |
| [0040](0040-traceable-http-error-reference.md) | Traceable HTTP error reference (500s → error_log + a user-quotable reference) | Accepted |
| [0041](0041-rawbt-bridge-transport.md) | RawBT bridge transport for Bluetooth-Classic thermal printers (Android) | Accepted |
| [0042](0042-go-live-official-coa-and-tax-rates.md) | Go-live: declare the chart of accounts + tax rates OFFICIAL | Accepted |
| [0043](0043-native-android-till-app.md) | Native Android till app — Capacitor thin-client + in-process NativePrint bridge | Proposed |
| [0044](0044-plan-tier-simple-mode.md) | Plan tier & Simple mode — UMKM feature curation | Proposed |
| [0045](0045-qris-modes-and-payment-service.md) | QRIS payment modes (manual / static image / Midtrans gateway) via a central payment-service | Accepted |
| [0046](0046-ingredient-inventory-phase1.md) | Ingredient-level inventory, phase 1: catalog + receive/adjust + stock opname (no recipes) | Accepted |
| [0047](0047-three-tier-pricing.md) | Three-tier pricing: Gratis / Basic / Premium + usage add-ons (display & soft-enforce phase) | Accepted |
| [0048](0048-minio-object-storage-for-media.md) | MinIO object storage for binary media (menu images, receipts, static QRIS) | Accepted |
| [0049](0049-business-and-employee-apps-outlet-terminal-auth.md) | Business + Employee apps; terminal auth = outlet credential + PIN operators + personal elevation | Proposed |
| [0050](0050-recipes-bom-costing.md) | Recipes / BOM + per-sale ingredient depletion + HPP costing | Accepted |
| [0051](0051-bundled-shell-with-ota-live-updates.md) | Bundled Android shell + OTA live updates for instant cold start | Proposed |
| [0052](0052-preset-role-based-access.md) | Preset role-based access — office vs floor roles, capability-gated at the gateway | Accepted |
| [0053](0053-environments-branching-release-pipeline.md) | Environments, branching & the release pipeline (dev → UAT → prod on a VPS) | Accepted |
| [0054](0054-company-scoped-employee-usernames.md) | Company-scoped employee usernames + realm brute-force hardening | Proposed |
| [0055](0055-employment-type-payroll-classification.md) | Employment-type payroll classification — pegawai tetap / bukan pegawai / pegawai tidak tetap | Proposed |
| [0056](0056-moving-average-inventory-cost.md) | Moving weighted-average inventory cost (operational scope; value+qty source of truth) | Accepted |
| [0057](0057-cloudflare-edge-rollback-first-prod-deploy.md) | Cloudflare-tunnel edge + rollback-first production deployment (co-located VPS) | Accepted |
| [0058](0058-per-environment-android-app-identities.md) | Per-environment Android app identities (UAT vs Prod, side-by-side) | Accepted |
| [0059](0059-english-first-localization.md) | English-first localization — Indonesian gated to Indonesia (company country in-app, browser time zone on public pages) | Accepted |
