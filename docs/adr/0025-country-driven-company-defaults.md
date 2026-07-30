# 25. Country-driven company defaults — Odoo-style signup, derived base currency, funnel fields

Date: 2026-07-30

## Status

Accepted

## Context

The public signup asked for an explicit base currency (IDR/USD choice cards), never learned who the
owner was (email only — no name anywhere in the product), and had no country concept, while the
product's benchmark (Odoo) derives currency, chart-of-accounts and tax localization from a single
country question and captures the owner's identity plus lightweight funnel data (company size,
primary interest, phone). The user decided to adopt the Odoo shape: country in, currency out
(fully derived), owner name in, phone/size/interest in, password unchanged (KC-user-first with
compensating delete stays — no activation-link rework).

## Decision

1. **The signup wire contract loses `baseCurrency` and gains `country` (ISO 3166-1 alpha-2).** The
   server derives the base currency in `company.domain.CountryDefaults`: `ID → IDR`, every other
   country `→ USD` — the platform's supported base-currency set is still exactly {IDR, USD} (the
   finance stack consolidates nothing else), so the derivation table is total. A stale body still
   carrying `baseCurrency` is ignored by deserialization (the `firstBusinessType` posture);
   derivation always wins — an API caller cannot pick a currency at all. Derivation runs BEFORE
   the Keycloak user is created, so an invalid country 400s with zero residue (no compensation
   spent on a validation error).

2. **`company` gains write-once `country` (CHAR(2), `updatable = false`, V9) plus nullable funnel
   columns** `phone`, `company_size`, `primary_interest`. "Settings live at creation" now reads:
   the *country* is the setting; the currency is its consequence — both immutable. No CHECK
   constraints (the V6 precedent: whitelists live in the aggregate/request edge). `ADD COLUMN …
   NOT NULL DEFAULT 'ID'` backfills without an UPDATE (no FORCE-RLS zero-row trap) and is a
   *correct* backfill — every pre-existing tenant is Indonesian.

3. **Owner identity goes on Keycloak's NATIVE `firstName`/`lastName`** (not custom attributes —
   the multi-company attribute merge never touches top-level fields). `ownerLastName` is OPTIONAL:
   Indonesian mononyms are first-class, not a validation error.

4. **`CompanyCreated` is NOT widened.** The only live consumer (entitlement) reads `company_id`
   alone; country/funnel fields are queryable state, not events. Rule 7 untouched — no schema, no
   catalog change.

5. **The in-app create-company path (`POST /api/v1/companies`) keeps its EXPLICIT `baseCurrency`**
   and gains only an optional `country` (null → `"ID"`). Second businesses/companies made by an
   existing operator carry no funnel data by design.

6. **Console signup becomes 5 steps** (Company → Region → About you → Security → Review). Country
   names are never hardcoded: a static ISO code list (`src/lib/countries.ts`) is rendered through
   `Intl.DisplayNames` in the active locale; the derived currency shows as a locked callout and a
   `Fixed at creation` review row whose edit pencil jumps to Region — changing the country IS
   changing the currency. Company size uses Odoo's bands (`1-5|6-20|21-50|51-250|250+`), primary
   interest Odoo's four (`own-company|client-services|student|teacher`). Phone is format-checked
   only — no SMS verification (no provider; deliberate).

## Consequences

- A company can genuinely sign up with USD books (any non-ID country) while the finance stack's
  tax engine remains Indonesian (PPN, e-Faktur) — the country column is the future hook for
  country-driven tax/CoA defaults; widening currency support = widening `CountryDefaults` + the
  finance FX surface, one deliberate platform decision in one place.
- The owner finally has a display name (Keycloak-native, flows into tokens/admin surfaces).
- Follow-ups deliberately out of scope: OnboardingWizard alignment (it still asks currency
  explicitly), exposing `country` on `CompanyResponse`, NPWP/tax-id capture (company settings,
  not signup), SMS verification.
- The 6-arg `CreateCompanyCommand` / 5-arg `Company` convenience constructors preserve ~25
  pre-existing call sites; new call sites should use the canonical constructors.
