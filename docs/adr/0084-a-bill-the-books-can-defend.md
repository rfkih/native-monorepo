# 0084. A bill the books can defend

- **Status:** Proposed
- **Date:** 2026-09-11
- **Deciders:** owner + Claude (finance-service + frontend)
- **Related:** [ADR 0015](0015-accounts-payable-subledger.md) (the AP aggregate; per-line accounts stay
  deferred), [ADR 0072](0072-purchase-linked-inventory-and-periodic-cogs-routing.md) (inventory
  lines and the purchase event this amends the value of), [ADR 0042](0042-go-live-official-coa-and-tax-rates.md)
  (the official 11 % PPN the boolean meant), [ADR 0048](0048-minio-object-storage-for-media.md) /
  [ADR 0063](0063-bill-attachments-private-media.md) (content-addressed private media, now on
  finance too), [ADR 0057](0057-cloudflare-edge-rollback-first-prod-deploy.md) (expand-only migrations),
  [ADR 0075](0075-navigation-and-overlay-contract.md) N2/N3 (one chrome, one Dialog primitive).
  Source design: `Tagihan Baru.dc.html` (Claude Design, 390×844).

## Context

The design's promise is one sentence: record an incoming vendor invoice and *refuse to save one
the books cannot defend* — a duplicate invoice number for the same vendor, a line without a price,
a computed total that differs from the figure printed on the paper, no evidence attached.

finance-service could not keep that promise. `POST /api/v1/ap/bills` took a vendor, a currency, a
boolean "taxable" and lines; the bill's number was an internal `BILL-00001` minted on post, its
dates were the posting day plus a term supplied at post time, VAT was a constant 11 %, and there
was no discount, no note, no vendor terms, no attachment — AP had no media at all. The console's
form (`features/ap/NewBill.tsx`) was the desktop page stacked, with no phone branch.

## Decision

**finance-service carries the invoice as the vendor wrote it.** Migration V69 is expand-only:

- `bill.vendor_invoice_number` (≤ 64, nullable) with a partial unique index
  `uq_bill_company_vendor_invoice (company_id, vendor_id, lower(number)) WHERE number IS NOT NULL
  AND status <> 'VOID'` — the SAME vendor may not carry a number twice on a live bill; a voided
  bill frees it (the void was the correction). The writer pre-checks and throws a typed 409
  `bill-duplicate-invoice`; `ApAdvice` also maps a race on the index to that type.
- `bill.bill_date` may now be set at draft time (the invoice date); `post` keeps it and dates the
  due date from it plus `term_days` (the draft's, unless the post overrides, else 30). **The GL
  entry is still dated on the posting day** — the bill date is the paper's date, the accounting
  date is when the books recorded it; no back-dated postings, no sealed-period guard needed.
- `bill.discount_minor` — a HEADER discount. `net = subtotal − discount` is the DPP; tax is on
  the net; `total = net + tax`. For the GL split and the purchase event the discount is spread
  across lines by the largest-remainder method (`DiscountAllocation`, integer minor units, Σ shares
  == discount, a line never below zero), so `expenseNet + inventoryNet == net` and the entry
  balances by construction; each `InventoryPurchaseRecorded` line value is net of its share.
- `bill.tax_bp ∈ {0, 1100, 1200}` — the PPN rate the invoice carries. The boolean stays on the
  wire for older clients and maps to 1100/0; existing taxable rows are backfilled to 1100.
- `bill.note` (≤ 1000, internal) and `vendor.payment_term_days` (the term the form preselects).
- `bill_attachment` — photo/PDF of the invoice, private: bytes in the object store under
  `finance/{company}/bill/{sha256}.{ext}` (a new `finance`-scoped MinIO user), metadata + RLS in
  Postgres, ≤ 5 MiB, ≤ 10 per bill, magic-byte validated, byte-identical re-uploads idempotent,
  served with the personal bearer + ETag/304 + private cache. VOID bills take no more evidence.

**The phone form is the checklist.** Below 640px `/bills/new` renders `NewBillPhone` (desktop
untouched): vendor card with terms and outstanding (from aging), invoice number with the live
same-vendor duplicate warning (from the vendor's bill list, backed by the 409), invoice date and
term chips → due date, line cards, summary (subtotal · diskon · DPP · PPN 0/11/12 % · total), the
reconciliation card, attachment + note, and the checklist that names every remaining reason, one
by one, above a sticky total + Save. The rules are one pure module, `features/ap/lib/newBillForm.ts`.
Save is a sequence, never a retry of a done step: create the DRAFT (all fields) → upload the staged
attachment → POST with the term → open the bill; a failed upload or post leaves the DRAFT and the
form locked with a retry / "open the draft".

**Product truth the design bent to:**
- The "akun biaya" menu is the one routing knob finance has (ADR 0015's deferral stands): `5000 ·
  Beban umum` (expense line) or `5100 · Persediaan bahan` (inventory line). Picking Persediaan turns
  the line into an ingredient line — picked from the catalog (the shared `IngredientPickerSheet`),
  quantity in the ingredient's shown unit, price per shown unit; the wire carries the ADR 0072 shape
  (`quantity: 1`, the total, `ingredientQtyBase`). The design's free unit list became the
  ingredient's own unit; expense lines have none.
- The printed-total reconciliation is a client-side gate only. The books hold the computed figures.
- The checklist requires the attachment (the design's "wajib untuk audit"); the server does not —
  a bill can exist without evidence, it just cannot be saved from this form.

## Consequences

**Deploy prerequisite.** finance-service now depends on `minio-init` and needs
`MEDIA_FINANCE_SECRET_KEY` in `prod.env` (and the UAT env) BEFORE the tag: an empty secret makes
`minio-init` fail, finance never starts, and the health gate rolls back. `minio-init` re-runs
idempotently on deploy and creates the `finance` user.

**Older clients keep working.** Every new request field is optional; the four-field body drafts a
bill exactly as before (11 % when taxable, no discount, dates on post). The desktop form is
unchanged and still creates drafts that way.

**The event's value changed meaning.** `InventoryPurchaseRecorded.lines[].value_minor` is now the
line total NET of its share of a header discount (identical when there is none). The schema is
unchanged; the catalog notes it.

**Open.** Per-line expense accounts (ADR 0015); attachments on the desktop form (it can view and
remove them on the bill; uploads come from the phone form); a dirty-form leave guard; a
per-vendor category (the design's "Bahan segar") — vendors carry no category.
