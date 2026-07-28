# 14. Accounts Receivable sub-ledger + the customer/party dimension in finance-service

Date: 2026-07-28

## Status

Accepted

## Context

The Odoo gap analysis scored Native's Accounting & Finance ≈ 38% vs Odoo. The double-entry GL,
chart of accounts, income statement, balance sheet, dimensional ledger, group consolidation, and
period close already exist — but the **transactional AR/AP layer** every accounting user lives in is
absent. Phase 1 of closing that gap is **Accounts Receivable**: customers, invoices (draft → issue),
payments/receipts, and an AR aging report.

This is the FIRST customer/party dimension in Native — no other service models an external
counterparty (org-service's `Company` is the tenant itself; a "user" is a Keycloak subject). Two
structural questions had to be decided: where AR lives, and how it posts to the books.

## Decision

1. **AR lives in finance-service** as a new `ar/` feature. Invoices post to the existing double-entry
   GL (`journal_entry`/`journal_line`) **in the same transaction** as the sub-ledger write. There is
   no cross-service synchronous call (rule 2 safe) — AR is finance-native, created directly by the
   accounting user, not produced by an upstream vertical service.

2. **The customer is finance-local.** A new `customer` aggregate (Auditable + FORCE RLS) created
   inside finance; `invoice.customer_id` references it. It is NOT a cross-service reference (rule 1).
   If a customer dimension later originates elsewhere (a CRM in a future phase), the standard
   pattern applies — an event → a finance-local read model — but Phase 1 owns it directly.

3. **A dedicated AR sub-ledger drives aging.** `invoice` / `invoice_line` / `invoice_payment` tables
   carry the customer + due-date + balance dimensions the GL `journal_line` does not (it has only
   `account_code`). Aging-by-customer reads the sub-ledger; the GL stays the balanced books.

4. **GL posting reuses the data-driven posting-template framework** (`JournalPostingService` +
   `role_account_map` + `posting_template`). A new generic method `buildEntryFromBreakdown` takes an
   `amount_basis → Money` map (reusing the existing `GROSS`/`GROSS_REVENUE`/`TAX` vocabulary), so AR
   posting is SME-pluggable data, not bespoke Java. New `AccountRole` constants (`AR`, `VAT_OUTPUT`,
   both already anticipated in the enum's javadoc) map to illustrative COA `1200` / `2200`; new
   `EventKind`s (`INVOICE_ISSUED`, `PAYMENT_RECEIVED`, `INVOICE_VOID`) key the seeded templates:
   - **Issue:** Dr AR (total) / Cr revenue (net) / Cr output VAT (tax) — the TAX line zero-omits for a
     non-taxable invoice.
   - **Payment:** Dr cash-clearing / Cr AR.
   - **Void:** the contra of issue.

5. **Revenue is recognised on issue (accrual)** in the GL, so an invoice flows into the GL-derived
   income statement + balance sheet (`1200 AR`, an ASSET) automatically. It does **not** feed the
   dimensional POS `/pnl` dashboard read models (those are fed only by `SaleRecorded`) — the
   authoritative accounting statements are the GL-derived ones.

6. **Single-currency (company base currency) in Phase 1**, matching the existing single-currency
   commission slice. Multi-currency AR (realized/unrealized FX) rides the deferred FX/IAS-21 SME gate.

7. **Output tax (PPN) is flagged-illustrative** — computed at an `ILLUSTRATIVE` 11% placeholder rate
   in `InvoiceWriter`; the invoice carries `uses_illustrative_rules=true` and the UI badges it
   "Estimated". The regime/rate are SME-gated, the same gate as the POS PB1/PPN tax (ADR 0006).

8. **Events are deferred to a Phase 1b.** Bookkeeping is fully synchronous within finance; the only
   consumer of `InvoiceIssued`/`PaymentReceived` would be notification-service (to email invoices),
   which has no real mail transport yet. Emitting them is deferred until a mail provider is chosen, so
   Phase 1 adds no new event to the catalog.

## Consequences

- Native gains its first customer/party dimension and a real AR transactional layer + aging, moving
  Accounting from ~38% toward ~50% of Odoo. AP (Phase 2) mirrors this pattern (a vendor party + a
  bill sub-ledger + the contra roles).
- The GL trial balance now includes AR (asset) and output-VAT (liability) balances; group
  consolidation and the statements pick these up with no change.
- **SME gates carried forward:** the AR account, the VAT regime/rate, and the accrual-vs-cash
  recognition basis are illustrative until an accounting/tax SME seeds real effective-dated data
  (V25 SME-confirmation block). e-Faktur / e-invoicing (Phase 4) is Indonesia-specific and SME-gated.
- **Deliberate Phase-1 exclusions:** multi-currency invoices, credit notes, recurring invoices,
  PDF/email delivery, dunning, and fractional line quantities — each a later sub-step.
- Verified: the full finance suite (367 tests) is green, including an end-to-end Testcontainers test
  (`ArTenancyIsolationTest`) that drives create → issue → part-pay against real PostgreSQL as the
  unprivileged `app_user` and proves cross-tenant RLS invisibility.
