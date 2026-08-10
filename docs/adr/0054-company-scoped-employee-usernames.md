# 0054. Company-scoped employee usernames + realm brute-force hardening

- **Status:** Accepted (Part 1 + Part 2 implemented, dual-reviewed PASS; deferred follow-ups below)
- **Date:** 2026-08-10
- **Deciders:** rifki, Claude (tech-lead)
- **Related:** [0021](0021-multi-company-ownership.md) (single realm, multivalued `company_id`), [0049](0049-business-and-employee-apps-outlet-terminal-auth.md) (outlet/device credential + PIN operators), [0013](0013-per-login-page-grants-subtractive-ui.md); CLAUDE.md hard rule 5 (tenant scoping). Keycloak client: `org.user.service.KeycloakAdminClient`.

## Context

All tenants share **one Keycloak realm** (`native`) — a deliberate consequence of [ADR 0021](0021-multi-company-ownership.md) (one login can belong to N companies via a multivalued `company_id` attribute). Keycloak enforces **username uniqueness per realm**, so today **every login username is globally unique across every company**.

Employees are invited via `UserService.inviteUser(username, …)` with a **free-form username** the owner/manager types. Three problems follow from the global namespace:

1. **Cross-tenant collisions.** Company A cannot use `budi` if any unrelated Company B already took it. The invite returns `409` — confusing, and it forces owners toward globally-unique-but-guessable names.
2. **Cross-tenant enumeration leak.** That same `409` on invite reveals a username exists *somewhere* in the realm — a small but real information leak across the tenant boundary (contrast the deliberate 404-not-403 anti-enumeration design already used for user *ids* in `UserService`).
3. **Low identifier entropy for brute-force / credential-stuffing.** Short, guessable local names (`kasir1`, `budi`) are easy to target when the attacker needs only the bare username.

The owner login is **not** affected: signup sets `username = email` (`KeycloakAdminClient.createUser`), and emails are already unique and non-sequential. The problem is specifically the **invited employee** logins.

**What actually stops password brute-force** is lockout + a password policy, not username secrecy — the realm had **neither** enabled. Company names are semi-public (storefront, receipts, URL), so scoping the username is **namespacing + enumeration friction (defence-in-depth)**, never the primary control.

Options considered:
- **A. Realm-per-company.** Strongest isolation, but breaks ADR 0021's single-login-N-companies model, the `company_id`-attribute machinery, and the single-issuer JWT validation at the gateway. Rejected — disproportionate.
- **B. Prefix the Keycloak username with an immutable company code** (`<code>.<local>`), keeping one realm. Chosen.
- **C. Two-field login (company + username).** Same stored identifier as B; a UX detail, folded into B below.

## Decision

**Two changes, one theme (raise the login-account bar without leaving the single realm).**

**1 — Realm brute-force hardening (implemented now, `docker/keycloak/native-realm.json`).**
Enable temporary brute-force lockout (`bruteForceProtected`, `permanentLockout=false`, `failureFactor=15`, escalating wait to `maxFailureWaitSeconds=900`) and a password policy (`length(10) and notUsername and notEmail and passwordHistory(3)`). `permanentLockout=false` on a shared realm avoids a lockout-as-DoS vector. The policy binds on the user's own `UPDATE_PASSWORD`/self-service change; Keycloak does not apply it to admin-set passwords, so the 16/24-char generated temp/device passwords are unaffected.

**2 — Company-scoped employee usernames (proposed; scoped design below).**
Every **invited employee** login's Keycloak username becomes **`<companyCode>.<local>`**, where:
- **`companyCode`** is a short (6-char), lowercase, **immutable** code minted on the company aggregate at company creation (org-service), collision-checked, stored in a new column and surfaced read-only in Settings. It is **not** the display name (mutable, non-ASCII) and **not** the raw UUID (unusable at a POS). Immutable like country / base currency.
- **`local`** is what the owner/manager types in the invite form (the "employee id"). Uniqueness is enforced on the **composed** username, so two companies may each have `budi`.

Login composition happens in the app: the Employee/Business app derives `companyCode` from context where possible (the app is company/outlet-bound) so staff often type only `local` + password/PIN; otherwise a Company-code field precedes the username field. Both apps use Keycloak's **hosted** login (a redirect, not a SPA password grant), so the composed `<code>.<local>` is passed as the **`login_hint`** pre-fill on the redirect (or the employee types it in full) — there is no client-side grant to intercept.

**Explicitly out of scope:** the owner login stays **email-as-username** (already unique, expected UX). Device/kiosk logins (`till.<outletId>`, ADR 0049) already carry outlet-UUID entropy — prefixing them with `companyCode` is a nice-to-have, deferred. No change to roles, the `company_id` attribute, the gateway, or any event contract. PIN operator tokens (ADR 0049) are unchanged — this is about the Keycloak account identifier only.

## Consequences

**Easier:** two tenants can reuse the same local employee name; the invite `409` becomes effectively per-company, closing the cross-tenant username-enumeration leak; guessing a working login now needs the company code *and* the local name *and* survives lockout.

**The rule code follows (for part 2, when implemented):**
- New immutable `company_code` column via a Flyway `ALTER` on the existing `company` table (org-service `V12`); the table already carries `Auditable` + the row-scoped RLS policy, so no `ALTER POLICY` is needed (mind the `NO FORCE`/`FORCE` wrapper on the backfill — a bare `UPDATE` under FORCE RLS matches zero rows). Minted at creation in the single choke point `CompanyService.createCompany` (covers signup + "add another business"), so every path gets one.
- **Uniqueness is arbitrated by a DB `UNIQUE` index + a mint-and-retry loop**, NOT an in-app pre-SELECT: under FORCE RLS a company can only ever see its own row, so a pre-check cannot detect another tenant's code. The retry lives in the non-transactional `CompanyService` (a unique-violation aborts the `REQUIRES_NEW` writer tx, so each attempt is a fresh tx).
- `UserService.inviteUser` / `KeycloakAdminClient.createInvitedUser` compose and store `<code>.<local>`; `usernameExists` checks the composed value. `UserResponse.username` surfaces the **local** part to the console (strip the `<code>.` prefix) — display-only, since every tenancy/self-lockout guard keys on the user **id**, never the username.
- Employee/Business app pre-fills the composed identifier via **`login_hint`** on the hosted-login redirect (derive code from context; fall back to a Company-code field) — there is no SPA password grant to intercept.
- **Backfill:** UAT is throwaway and not pushed — rename existing non-device human usernames to `<code>.<local>` via the admin API once, or recreate the handful of test logins. No production data exists yet.

**Costs / follow-ups:**
- Applying part 1 to the **running** UAT Keycloak must be a **live realm-settings update** (`PUT /admin/realms/native`), **not** a realm re-import (re-import drops the KC DB and wipes users — see the keycloak-persistent-postgres note). The JSON here is the source of truth for fresh imports.
- **DONE (landed with part 1):** the owner signup password now enforces `@Size(min = 10)` + a not-equal-to-email check in `SignupRequest` — the admin-set owner login bypasses the realm policy, so this mirrors `length(10)`/`notEmail` at the API edge (security review HIGH #1).
- **Realm follow-ups from the security review (not blocking part 1):** enable Keycloak auth-event logging (`eventsEnabled`/`adminEventsEnabled` + jboss-logging, admin-event *details* OFF to respect rule 6, don't co-mingle with the app logs the PII gate guards); set `sslRequired=external` for prod; consider tightening `failureFactor` to ~10 and adding **MFA for owner/accountant** back-office roles (the real banking-grade upgrade — brute-force tuning has diminishing returns).
- Part 2 is **IMPLEMENTED** (org-service `V12` + minting/retry + invite composition + prefix-strip + read surfacing; console session/`CreateLoginDialog`; employee-app `login_hint`). Dual-reviewed (security PASS; code PASS-with-warnings) — the warnings landed: the display strip now matches the tenant's EXACT `company_code` prefix (not a shape regex, so no `@`-edge / false-positive), collision detection prefers `ConstraintViolationException.getConstraintName()` with a message fallback, and a `log.warn` makes a saturated code space observable.
- Enforcement: `UserManagementAcceptanceTest` now proves two tenants hold the same local name (per-company 409), the stored KC username carries the prefix, the response + list show local only, and same-tenant duplicates 409 — all green against real Postgres + Keycloak, alongside the mint-path tests (Signup / MultiCompany) and `LayeredArchitectureTest`.
- **Deferred follow-ups (non-blocking):** (1) a focused test that seeds a `company_code` then calls `CompanyWriter.create` with the same code to assert `CompanyCodeCollisionException` (the mechanism is fail-closed today — a missed collision is a retryable 500, not corruption); (2) a read-only `company_code` row in console Settings (it currently surfaces in the invite dialog); (3) case-normalize the employee-ID field in the employee-app `login_hint` (KC usernames are case-insensitive, so cosmetic).
