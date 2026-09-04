# 0059 — English-first localization: Indonesian gated to Indonesia

Status: Accepted (2026-08-13)

## Context

Native began as an Indonesia-first product: the console auto-selected Bahasa Indonesia for any
browser whose `navigator.language` started with `id`, and the language switcher offered EN/ID to
every user on every surface — public landing, signup, and the in-app console alike. The company
model already stores three creation-time settings ([ADR 0025](0025-country-driven-company-defaults.md)):
`country` and its **derived, immutable** base currency (`ID → IDR`, else `USD`), and a
`defaultLanguage`. Currency was gated on country; language was not — a company in any country could
be created in Indonesian, and the stored `defaultLanguage` was in fact never applied to the UI at
all.

The owner is taking the product to a **global market**. The requirement: **English is the default
everywhere; Indonesian is offered only when the location is Indonesia** (and where it is offered,
users may still choose English).

There is no geo-IP backend, so "location" is resolved from the strongest signals already available:
the active company's country when signed in, and the browser time zone on public pages.

## Decision

**One policy, one predicate — `offeredLangs(indonesia)`**: English is always offered; Indonesian
only when the context is Indonesia. Adding a language later is extending this function and the
locale files; every gate reads from it.

**"Indonesia" is resolved by context:**

- **Authenticated / in-app** → the active company's country is Indonesia, via the EXACT proxy
  `baseCurrency === 'IDR'` (ADR 0025 makes IDR ⇔ Indonesia biconditional) — no new field, no backend
  read change.
- **Public** (landing, and signup before a country is chosen) → the browser's IANA time zone is one
  of Indonesia's four (`Asia/Jakarta · Pontianak · Makassar · Jayapura`). Time zone reflects
  physical location, not the phone's UI language.

**Language selection (console, `frontend/console/src/i18n`):**

- Boot defaults to English, or Indonesian when the time zone is Indonesian — `navigator.language` is
  no longer a trigger.
- On the active company resolving, the UI **adopts the company's `defaultLanguage`** (finally wired
  through) and **clamps** anything not offered here to English — e.g. a stored Indonesian preference
  on a now-non-Indonesian company. A manual toggle is persisted and **wins**; auto-selection never
  writes storage, so "the user chose this" stays distinguishable from "we chose it".
- The `LanguageSwitcher` renders only offered languages, and **nothing** when English is the only
  one. The reconciliation lives in `SessionProvider`, so the **employee app** (which reuses it and
  `@/i18n` via the `@` alias) follows the same policy for free.

**Signup / add-company:** the language chooser appears only when the selected country is Indonesia;
every other country is locked to English (shown as a note, mirroring the locked-currency treatment).
The country seeds from the browser location.

**Server invariant (authoritative):** the country ↔ language rule lives in `CountryDefaults`
alongside the currency derivation — English for any country, Indonesian only for `ID`. `SignupService`
applies it at the same **derive-before-create** point as the country check (so a disallowed language
`400`s before any Keycloak user exists — no compensation spent), and the `Company` aggregate enforces
it on every create path (the authoritative check for the in-app path, which has no Keycloak step).

## Consequences

- Non-Indonesian companies can no longer be created in Indonesian — a real behavior change. A
  US-in-Indonesian signup, previously accepted, is now a clean `400`. Existing acceptance tests that
  relied on the old behavior are updated to the new policy.
- A pre-existing non-Indonesian company that somehow stored `defaultLanguage = id` is harmlessly
  clamped to English in-app — no data migration.
- No DB schema change, no event/catalog change; `default_language` keeps `en|id`, now country-gated.
- The book/report **presentation** stays currency- and locale-formatted via `Intl` regardless of UI
  language (rule 9) — this ADR changes which UI language is offered, not how money/dates render.

## Out of scope (future)

Additional languages (each is an `offeredLangs` branch + a locale file + a widened whitelist); a
true geo-IP signal for public pages if time zone proves too coarse; a per-user language preference
persisted server-side on the profile (today it is a client-local explicit choice).
